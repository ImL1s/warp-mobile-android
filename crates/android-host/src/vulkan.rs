//! Android Vulkan render harness (M2-S04 runtime path).
//!
//! This module is the **runtime harness** that the JNI layer drives. It
//! mirrors the canonical Vulkan implementation in
//! `warp-src/crates/warpui/src/platform/android/vulkan.rs` so we have an
//! end-to-end runtime path on device for M2-S04 verification without yet
//! solving the cross-workspace Cargo dependency between this crate and
//! `warpui` (warp-src has workspace.package inheritance the main repo lacks;
//! unifying them is M3 scope).
//!
//! Both implementations are derived from the M0 spike at
//! `spikes/vulkan-surface-recreate/src/lib.rs` which validated lifecycle on
//! Adreno 6xx+ (S24 Ultra Adreno 750 p95=18ms, S21+ Adreno 660 p95=28ms over
//! 100 swapchain recreates).
//!
//! ## Design
//!
//! - Single process-wide `Mutex<Option<VulkanSurface>>` holds the entire
//!   Vulkan state. JNI exports drive lifecycle via
//!   `surface_attach`/`surface_detach`/`render_clear`.
//! - VK_LAYER_KHRONOS_validation enabled in debug builds; clean steady run is
//!   a hard M2-S04 acceptance gate.
//! - VK_ERROR_OUT_OF_DATE_KHR / VK_SUBOPTIMAL_KHR triggers swapchain recreate
//!   per Plan Amendment 2 hardened acceptance.
//! - Present mode FIFO (vsync-locked); image count = min_image_count + 1
//!   (typically 2-3 on Adreno).
//!
//! ## Web-search references consulted (2026-04-30):
//! - ash 0.38 swapchain example:
//!   <https://github.com/ash-rs/ash/blob/0.38.0/ash-examples/src/bin/triangle.rs>
//! - VK_KHR_android_surface man page:
//!   <https://registry.khronos.org/vulkan/specs/latest/man/html/VK_KHR_android_surface.html>
//! - ANativeWindow_fromSurface NDK reference:
//!   <https://developer.android.com/ndk/reference/group/a-native-window#anativewindow_fromsurface>
//! - VK_ERROR_OUT_OF_DATE_KHR handling pattern from ash examples + Vulkan
//!   spec swapchain VUID-vkAcquireNextImageKHR-semaphore-01779.
//!
//! ## M2-S05 web-search references (frame capture):
//! - SaschaWillems Vulkan screenshot example (canonical readback flow):
//!   <https://github.com/SaschaWillems/Vulkan/blob/master/examples/screenshot/screenshot.cpp>
//! - Khronos Vulkan-Docs synchronization examples (image-layout transitions):
//!   <https://github.com/KhronosGroup/Vulkan-Docs/wiki/Synchronization-Examples>
//! - vkCmdCopyImageToBuffer + host-coherent staging buffer pattern:
//!   <https://docs.vulkan.org/guide/latest/synchronization_examples.html>
//! - ARM mobile best-practice on swapchain TRANSFER_SRC usage:
//!   <https://github.com/ARM-software/vulkan_best_practice_for_mobile_developers/blob/master/docs/faq.md>
//! - png crate (pure-Rust PNG encoder, used over `image` to keep ABI lean):
//!   <https://github.com/image-rs/image-png>

#![cfg(target_os = "android")]

use std::sync::Mutex;

use ash::vk;

/// Whether to enable validation layers. Default ON in debug builds.
const VALIDATION_LAYERS: bool = cfg!(debug_assertions);

#[allow(dead_code)] // some fields kept for ordered RAII teardown.
struct VulkanSurface {
    entry: ash::Entry,
    instance: ash::Instance,
    surface_loader: ash::khr::surface::Instance,
    surface: vk::SurfaceKHR,
    native_window: *mut ndk_sys::ANativeWindow,
    phys_device: vk::PhysicalDevice,
    queue_family: u32,
    device: ash::Device,
    swapchain_loader: ash::khr::swapchain::Device,
    swapchain: vk::SwapchainKHR,
    surface_format: vk::Format,
    extent: vk::Extent2D,
    render_pass: vk::RenderPass,
    framebuffers: Vec<vk::Framebuffer>,
    image_views: Vec<vk::ImageView>,
    /// M2-S05: handles to the swapchain images (parallel to `image_views`).
    /// Owned by `VkSwapchainKHR` — freed implicitly on swapchain destroy.
    swapchain_images: Vec<vk::Image>,
    /// M2-S05: whether swapchain images were created with TRANSFER_SRC usage.
    /// Driven from `caps.supported_usage_flags`; false → capture path soft-fails.
    swapchain_supports_transfer_src: bool,
    command_pool: vk::CommandPool,
    command_buffers: Vec<vk::CommandBuffer>,
    graphics_queue: vk::Queue,
    image_available: vk::Semaphore,
    /// Round-3 (Codex round-2 blocker 2): per-swapchain-image present-wait
    /// semaphore. Indexed by the `image_index` returned from
    /// `vkAcquireNextImageKHR` so each presented image carries its own wait
    /// semaphore — `queue_wait_idle` is NOT a portable substitute for
    /// per-image semaphore reuse per the Vulkan guide:
    ///   <https://docs.vulkan.org/guide/latest/swapchain_semaphore_reuse.html>
    /// Vec length = framebuffers.len() (= swapchain image count). Recreated
    /// alongside the swapchain in `recreate_swapchain`.
    render_finished_per_image: Vec<vk::Semaphore>,
    fence: vk::Fence,
    debug_utils_loader: Option<ash::ext::debug_utils::Instance>,
    debug_messenger: vk::DebugUtilsMessengerEXT,
    /// M2-S08: optional static-glyph-grid renderer. Populated by
    /// `attach_static_grid()` after surface init; consumed by
    /// `submit_grid_frame()`. None ⇒ render path falls back to clear-color.
    static_grid: Option<crate::static_grid::StaticGrid>,
    /// M3-S08: optional per-cell dynamic-grid renderer (runtime mirror).
    /// Populated by `init_dynamic_grid()`; consumed by
    /// `submit_dynamic_grid_frame()`. Coexists with `static_grid` so the
    /// M2-S08 demo path stays usable when terminal_mode is off.
    dynamic_grid: Option<crate::dynamic_grid::DynamicGrid>,
}

// SAFETY: ANativeWindow* is ref-counted (NDK contract). Vulkan handles are
// externally synchronized; Mutex guarantees single-threaded access.
unsafe impl Send for VulkanSurface {}

static SURFACE_STATE: Mutex<Option<VulkanSurface>> = Mutex::new(None);
static FRAME_COUNTER: Mutex<u64> = Mutex::new(0);

fn uptime_millis() -> i64 {
    let mut ts = libc::timespec {
        tv_sec: 0,
        tv_nsec: 0,
    };
    // SAFETY: clock_gettime accepts a valid out-pointer.
    unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut ts) };
    ts.tv_sec as i64 * 1000 + ts.tv_nsec as i64 / 1_000_000
}

unsafe extern "system" fn vulkan_debug_callback(
    message_severity: vk::DebugUtilsMessageSeverityFlagsEXT,
    _message_type: vk::DebugUtilsMessageTypeFlagsEXT,
    p_callback_data: *const vk::DebugUtilsMessengerCallbackDataEXT,
    _user_data: *mut std::ffi::c_void,
) -> vk::Bool32 {
    if !p_callback_data.is_null() {
        let msg = std::ffi::CStr::from_ptr((*p_callback_data).p_message);
        let text = msg.to_string_lossy();
        // Tag prefix `[VkVal]` is parsed by tools/scripts/test-render-scene.sh
        // to detect any non-debug validation messages.
        if message_severity.contains(vk::DebugUtilsMessageSeverityFlagsEXT::ERROR) {
            log::error!(target: "WarpVulkan", "[VkVal] {}", text);
        } else if message_severity.contains(vk::DebugUtilsMessageSeverityFlagsEXT::WARNING) {
            log::warn!(target: "WarpVulkan", "[VkVal] {}", text);
        } else {
            log::debug!(target: "WarpVulkan", "[VkVal] {}", text);
        }
    }
    vk::FALSE
}

fn create_vulkan_instance(entry: &ash::Entry) -> Result<ash::Instance, vk::Result> {
    let app_info = vk::ApplicationInfo::default()
        .application_name(c"warp-mobile")
        .application_version(vk::make_api_version(0, 0, 1, 0))
        .engine_name(c"warp-android-host")
        .engine_version(vk::make_api_version(0, 0, 1, 0))
        .api_version(vk::API_VERSION_1_1);

    let mut extension_names: Vec<*const std::ffi::c_char> = vec![
        ash::khr::surface::NAME.as_ptr(),
        ash::khr::android_surface::NAME.as_ptr(),
    ];
    if VALIDATION_LAYERS {
        extension_names.push(ash::ext::debug_utils::NAME.as_ptr());
    }

    let layer_names: Vec<*const std::ffi::c_char> = if VALIDATION_LAYERS {
        // SAFETY: enumerate_instance_layer_properties is safe with a loaded entry.
        let available =
            unsafe { entry.enumerate_instance_layer_properties() }.unwrap_or_default();
        let khronos_val = c"VK_LAYER_KHRONOS_validation";
        let found = available.iter().any(|l| {
            // SAFETY: layer_name is always a NUL-terminated C string per spec.
            let name = unsafe { std::ffi::CStr::from_ptr(l.layer_name.as_ptr()) };
            name == khronos_val
        });
        if found {
            log::info!(target: "WarpVulkan", "VK_LAYER_KHRONOS_validation enabled");
            vec![khronos_val.as_ptr()]
        } else {
            // Round-2 (Codex blocker 4b): in debug builds, validation layer is
            // a HARD M2-S04 acceptance gate. Silently warning + continuing led
            // to a false-positive PASS where the test driver saw zero
            // validation lines and reported `validation_clean=true` regardless.
            // Production (release) builds skip the layer entirely (above
            // VALIDATION_LAYERS check).
            log::error!(target: "WarpVulkan",
                "VK_LAYER_KHRONOS_validation NOT available — debug build packaging is broken");
            panic!("VK_LAYER_KHRONOS_validation layer required in debug builds; \
                    ensure libVkLayer_khronos_validation.so is packaged in jniLibs \
                    (run android/gradlew :app:fetchValidationLayer or set ANDROID_VALIDATION_LAYER_SO)");
        }
    } else {
        vec![]
    };

    let create_info = vk::InstanceCreateInfo::default()
        .application_info(&app_info)
        .enabled_extension_names(&extension_names)
        .enabled_layer_names(&layer_names);
    // SAFETY: pointer arrays outlive this call.
    unsafe { entry.create_instance(&create_info, None) }
}

fn setup_debug_messenger(
    entry: &ash::Entry,
    instance: &ash::Instance,
) -> (
    Option<ash::ext::debug_utils::Instance>,
    vk::DebugUtilsMessengerEXT,
) {
    if !VALIDATION_LAYERS {
        return (None, vk::DebugUtilsMessengerEXT::null());
    }
    let loader = ash::ext::debug_utils::Instance::new(entry, instance);
    let create_info = vk::DebugUtilsMessengerCreateInfoEXT::default()
        .message_severity(
            vk::DebugUtilsMessageSeverityFlagsEXT::ERROR
                | vk::DebugUtilsMessageSeverityFlagsEXT::WARNING
                | vk::DebugUtilsMessageSeverityFlagsEXT::INFO,
        )
        .message_type(
            vk::DebugUtilsMessageTypeFlagsEXT::GENERAL
                | vk::DebugUtilsMessageTypeFlagsEXT::VALIDATION
                | vk::DebugUtilsMessageTypeFlagsEXT::PERFORMANCE,
        )
        .pfn_user_callback(Some(vulkan_debug_callback));
    // SAFETY: loader/instance live as long as the messenger.
    let messenger = unsafe { loader.create_debug_utils_messenger(&create_info, None) }
        .unwrap_or_else(|e| {
            log::warn!(target: "WarpVulkan", "create_debug_utils_messenger failed: {:?}", e);
            vk::DebugUtilsMessengerEXT::null()
        });
    (Some(loader), messenger)
}

unsafe fn create_surface_from_native_window(
    entry: &ash::Entry,
    instance: &ash::Instance,
    native_window: *mut ndk_sys::ANativeWindow,
) -> Result<vk::SurfaceKHR, vk::Result> {
    let android_surface_loader = ash::khr::android_surface::Instance::new(entry, instance);
    let create_info = vk::AndroidSurfaceCreateInfoKHR::default()
        .window(native_window as *mut ash::vk::ANativeWindow);
    android_surface_loader.create_android_surface(&create_info, None)
}

fn find_graphics_queue_family(
    instance: &ash::Instance,
    phys_device: vk::PhysicalDevice,
) -> Option<u32> {
    // SAFETY: phys_device owned by instance.
    let queue_families =
        unsafe { instance.get_physical_device_queue_family_properties(phys_device) };
    queue_families
        .iter()
        .enumerate()
        .find_map(|(i, props)| {
            if props.queue_flags.contains(vk::QueueFlags::GRAPHICS) {
                Some(i as u32)
            } else {
                None
            }
        })
}

fn select_physical_device(
    instance: &ash::Instance,
    surface_loader: &ash::khr::surface::Instance,
    surface: vk::SurfaceKHR,
) -> Option<(vk::PhysicalDevice, u32)> {
    // SAFETY: enumerate_physical_devices has no preconditions.
    let devices = unsafe { instance.enumerate_physical_devices() }.unwrap_or_default();
    log::info!(target: "WarpVulkan", "physical_device_count={}", devices.len());
    for dev in &devices {
        // SAFETY: dev came from enumerate_physical_devices.
        let props = unsafe { instance.get_physical_device_properties(*dev) };
        let name = unsafe { std::ffi::CStr::from_ptr(props.device_name.as_ptr()) };
        let Some(qf) = find_graphics_queue_family(instance, *dev) else {
            log::info!(target: "WarpVulkan",
                "device={} no_graphics_queue", name.to_string_lossy());
            continue;
        };
        let supported =
            unsafe { surface_loader.get_physical_device_surface_support(*dev, qf, surface) }
                .unwrap_or(false);
        log::info!(target: "WarpVulkan",
            "device={} queue_family={} surface_support={}",
            name.to_string_lossy(), qf, supported);
        if supported {
            return Some((*dev, qf));
        }
    }
    log::error!(target: "WarpVulkan",
        "no_suitable_physical_device count={}", devices.len());
    None
}

unsafe fn create_swapchain_and_dependents(
    surface_loader: &ash::khr::surface::Instance,
    phys_device: vk::PhysicalDevice,
    queue_family: u32,
    surface: vk::SurfaceKHR,
    device: &ash::Device,
    swapchain_loader: &ash::khr::swapchain::Device,
    old_swapchain: vk::SwapchainKHR,
) -> Result<
    (
        vk::SwapchainKHR,
        vk::Format,
        vk::Extent2D,
        vk::RenderPass,
        Vec<vk::ImageView>,
        Vec<vk::Framebuffer>,
        Vec<vk::Image>, // M2-S05: raw swapchain images
        bool,           // M2-S05: TRANSFER_SRC enabled flag
        vk::CommandPool,
        Vec<vk::CommandBuffer>,
    ),
    vk::Result,
> {
    let formats = surface_loader
        .get_physical_device_surface_formats(phys_device, surface)
        .unwrap_or_default();
    let format = formats
        .iter()
        .find(|f| {
            f.format == vk::Format::B8G8R8A8_UNORM || f.format == vk::Format::R8G8B8A8_UNORM
        })
        .or_else(|| formats.first())
        .copied()
        .unwrap_or(vk::SurfaceFormatKHR {
            format: vk::Format::B8G8R8A8_UNORM,
            color_space: vk::ColorSpaceKHR::SRGB_NONLINEAR,
        });

    // FIFO per Plan §6 M2 row #3 + M2-S04 AC: vsync-locked, no tearing.
    let present_mode = vk::PresentModeKHR::FIFO;

    let caps = surface_loader.get_physical_device_surface_capabilities(phys_device, surface)?;
    let extent = if caps.current_extent.width != u32::MAX {
        caps.current_extent
    } else {
        vk::Extent2D {
            width: 1080,
            height: 2400,
        }
    };

    let image_count = (caps.min_image_count + 1)
        .min(if caps.max_image_count > 0 {
            caps.max_image_count
        } else {
            u32::MAX
        });

    // Pick a supported composite-alpha mode. Adreno 830 (S25) does not support
    // OPAQUE — we must query supportedCompositeAlpha and pick one of
    // {OPAQUE, INHERIT, PRE_MULTIPLIED, POST_MULTIPLIED}. INHERIT is the
    // safest fallback on Android since the SurfaceFlinger handles compositing.
    let composite_alpha = if caps.supported_composite_alpha.contains(vk::CompositeAlphaFlagsKHR::OPAQUE) {
        vk::CompositeAlphaFlagsKHR::OPAQUE
    } else if caps.supported_composite_alpha.contains(vk::CompositeAlphaFlagsKHR::INHERIT) {
        vk::CompositeAlphaFlagsKHR::INHERIT
    } else if caps.supported_composite_alpha.contains(vk::CompositeAlphaFlagsKHR::PRE_MULTIPLIED) {
        vk::CompositeAlphaFlagsKHR::PRE_MULTIPLIED
    } else if caps.supported_composite_alpha.contains(vk::CompositeAlphaFlagsKHR::POST_MULTIPLIED) {
        vk::CompositeAlphaFlagsKHR::POST_MULTIPLIED
    } else {
        // Defensive — spec guarantees at least one mode supported, so this
        // path is unreachable on a conformant driver.
        log::warn!(target: "WarpVulkan",
            "no composite-alpha mode advertised; defaulting to INHERIT");
        vk::CompositeAlphaFlagsKHR::INHERIT
    };
    log::info!(target: "WarpVulkan",
        "composite_alpha selected: {:?} (supported_mask=0x{:x})",
        composite_alpha, caps.supported_composite_alpha.as_raw());

    // M2-S05: enable TRANSFER_SRC on swapchain images so `vkCmdCopyImageToBuffer`
    // can read directly from a presentable image. Spec REQUIRES the driver to
    // advertise this in `caps.supported_usage_flags` for non-fullscreen
    // surfaces, but we MUST query and conditionally enable to avoid
    // VUID-VkSwapchainCreateInfoKHR-imageUsage-01427 on drivers that don't.
    // Refs:
    //   <https://github.com/SaschaWillems/Vulkan/blob/master/examples/screenshot/screenshot.cpp>
    //   <https://github.com/ARM-software/vulkan_best_practice_for_mobile_developers/blob/master/docs/faq.md>
    let mut image_usage = vk::ImageUsageFlags::COLOR_ATTACHMENT;
    if caps
        .supported_usage_flags
        .contains(vk::ImageUsageFlags::TRANSFER_SRC)
    {
        image_usage |= vk::ImageUsageFlags::TRANSFER_SRC;
    } else {
        log::warn!(target: "WarpVulkan",
            "swapchain TRANSFER_SRC NOT advertised; capture path will soft-fail \
             (supported_usage_flags=0x{:x})",
            caps.supported_usage_flags.as_raw());
    }

    let swapchain_create_info = vk::SwapchainCreateInfoKHR::default()
        .surface(surface)
        .min_image_count(image_count)
        .image_format(format.format)
        .image_color_space(format.color_space)
        .image_extent(extent)
        .image_array_layers(1)
        .image_usage(image_usage)
        .image_sharing_mode(vk::SharingMode::EXCLUSIVE)
        .pre_transform(caps.current_transform)
        .composite_alpha(composite_alpha)
        .present_mode(present_mode)
        .clipped(true)
        .old_swapchain(old_swapchain);

    let swapchain = swapchain_loader.create_swapchain(&swapchain_create_info, None)?;

    // Render pass — clear → present_src layout.
    let attachment = vk::AttachmentDescription::default()
        .format(format.format)
        .samples(vk::SampleCountFlags::TYPE_1)
        .load_op(vk::AttachmentLoadOp::CLEAR)
        .store_op(vk::AttachmentStoreOp::STORE)
        .stencil_load_op(vk::AttachmentLoadOp::DONT_CARE)
        .stencil_store_op(vk::AttachmentStoreOp::DONT_CARE)
        .initial_layout(vk::ImageLayout::UNDEFINED)
        .final_layout(vk::ImageLayout::PRESENT_SRC_KHR);
    let color_ref = vk::AttachmentReference::default()
        .attachment(0)
        .layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL);
    let color_refs = [color_ref];
    let subpass = vk::SubpassDescription::default()
        .pipeline_bind_point(vk::PipelineBindPoint::GRAPHICS)
        .color_attachments(&color_refs);
    let dependency = vk::SubpassDependency::default()
        .src_subpass(vk::SUBPASS_EXTERNAL)
        .dst_subpass(0)
        .src_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
        .src_access_mask(vk::AccessFlags::empty())
        .dst_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
        .dst_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE);
    let attachments = [attachment];
    let subpasses = [subpass];
    let dependencies = [dependency];
    let rp_info = vk::RenderPassCreateInfo::default()
        .attachments(&attachments)
        .subpasses(&subpasses)
        .dependencies(&dependencies);
    let render_pass = device.create_render_pass(&rp_info, None)?;

    // Framebuffers.
    let images = swapchain_loader.get_swapchain_images(swapchain)?;
    let mut image_views = Vec::with_capacity(images.len());
    let mut framebuffers = Vec::with_capacity(images.len());
    for image in &images {
        let view_info = vk::ImageViewCreateInfo::default()
            .image(*image)
            .view_type(vk::ImageViewType::TYPE_2D)
            .format(format.format)
            .components(vk::ComponentMapping::default())
            .subresource_range(
                vk::ImageSubresourceRange::default()
                    .aspect_mask(vk::ImageAspectFlags::COLOR)
                    .base_mip_level(0)
                    .level_count(1)
                    .base_array_layer(0)
                    .layer_count(1),
            );
        let view = device.create_image_view(&view_info, None)?;
        image_views.push(view);
        let attach = [view];
        let fb_info = vk::FramebufferCreateInfo::default()
            .render_pass(render_pass)
            .attachments(&attach)
            .width(extent.width)
            .height(extent.height)
            .layers(1);
        let fb = device.create_framebuffer(&fb_info, None)?;
        framebuffers.push(fb);
    }

    // Command pool + buffers (one per swapchain image).
    let pool_info = vk::CommandPoolCreateInfo::default()
        .queue_family_index(queue_family)
        .flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER);
    let command_pool = device.create_command_pool(&pool_info, None)?;
    let alloc_info = vk::CommandBufferAllocateInfo::default()
        .command_pool(command_pool)
        .level(vk::CommandBufferLevel::PRIMARY)
        .command_buffer_count(framebuffers.len() as u32);
    let command_buffers = device.allocate_command_buffers(&alloc_info)?;

    let swapchain_supports_transfer_src = image_usage.contains(vk::ImageUsageFlags::TRANSFER_SRC);

    Ok((
        swapchain,
        format.format,
        extent,
        render_pass,
        image_views,
        framebuffers,
        images,
        swapchain_supports_transfer_src,
        command_pool,
        command_buffers,
    ))
}

fn init_surface(
    native_window: *mut ndk_sys::ANativeWindow,
) -> Result<VulkanSurface, vk::Result> {
    // SAFETY: Entry::load loads the system Vulkan loader; safe.
    let entry = unsafe { ash::Entry::load() }.map_err(|e| {
        log::error!(target: "WarpVulkan", "ash::Entry::load failed: {:?}", e);
        vk::Result::ERROR_INITIALIZATION_FAILED
    })?;

    let instance = create_vulkan_instance(&entry)?;
    let (debug_utils_loader, debug_messenger) = setup_debug_messenger(&entry, &instance);

    let surface = match unsafe {
        create_surface_from_native_window(&entry, &instance, native_window)
    } {
        Ok(s) => s,
        Err(e) => {
            // SAFETY: we own these handles on this failure path.
            unsafe {
                if let Some(loader) = &debug_utils_loader {
                    if debug_messenger != vk::DebugUtilsMessengerEXT::null() {
                        loader.destroy_debug_utils_messenger(debug_messenger, None);
                    }
                }
                instance.destroy_instance(None);
            }
            return Err(e);
        }
    };

    let surface_loader = ash::khr::surface::Instance::new(&entry, &instance);
    let (phys_device, queue_family) =
        match select_physical_device(&instance, &surface_loader, surface) {
            Some(x) => x,
            None => {
                unsafe {
                    surface_loader.destroy_surface(surface, None);
                    if let Some(loader) = &debug_utils_loader {
                        if debug_messenger != vk::DebugUtilsMessengerEXT::null() {
                            loader.destroy_debug_utils_messenger(debug_messenger, None);
                        }
                    }
                    instance.destroy_instance(None);
                }
                return Err(vk::Result::ERROR_INITIALIZATION_FAILED);
            }
        };

    // Logical device.
    let queue_priorities = [1.0f32];
    let queue_create_info = vk::DeviceQueueCreateInfo::default()
        .queue_family_index(queue_family)
        .queue_priorities(&queue_priorities);
    let device_extensions = [ash::khr::swapchain::NAME.as_ptr()];
    let device_create_info = vk::DeviceCreateInfo::default()
        .queue_create_infos(std::slice::from_ref(&queue_create_info))
        .enabled_extension_names(&device_extensions);
    let device = unsafe { instance.create_device(phys_device, &device_create_info, None) }?;
    let graphics_queue = unsafe { device.get_device_queue(queue_family, 0) };

    let swapchain_loader = ash::khr::swapchain::Device::new(&instance, &device);

    let (
        swapchain,
        format,
        extent,
        render_pass,
        image_views,
        framebuffers,
        swapchain_images,
        swapchain_supports_transfer_src,
        command_pool,
        command_buffers,
    ) = unsafe {
        create_swapchain_and_dependents(
            &surface_loader,
            phys_device,
            queue_family,
            surface,
            &device,
            &swapchain_loader,
            vk::SwapchainKHR::null(),
        )
    }?;

    let sem_info = vk::SemaphoreCreateInfo::default();
    let fence_info = vk::FenceCreateInfo::default();
    let image_available = unsafe { device.create_semaphore(&sem_info, None) }?;
    // Round-3 (Codex round-2 blocker 2): per-image present-wait semaphores.
    // <https://docs.vulkan.org/guide/latest/swapchain_semaphore_reuse.html>
    let mut render_finished_per_image = Vec::with_capacity(framebuffers.len());
    for _ in 0..framebuffers.len() {
        let sem = unsafe { device.create_semaphore(&sem_info, None) }?;
        render_finished_per_image.push(sem);
    }
    let fence = unsafe { device.create_fence(&fence_info, None) }?;

    Ok(VulkanSurface {
        entry,
        instance,
        surface_loader,
        surface,
        native_window,
        phys_device,
        queue_family,
        device,
        swapchain_loader,
        swapchain,
        surface_format: format,
        extent,
        render_pass,
        framebuffers,
        image_views,
        swapchain_images,
        swapchain_supports_transfer_src,
        command_pool,
        command_buffers,
        graphics_queue,
        image_available,
        render_finished_per_image,
        fence,
        debug_utils_loader,
        debug_messenger,
        static_grid: None,
        dynamic_grid: None,
    })
}

unsafe fn recreate_swapchain(s: &mut VulkanSurface) -> Result<(), vk::Result> {
    let t0 = uptime_millis();
    s.device.device_wait_idle()?;

    // M2-S08: the static-grid pipeline references the current render_pass and
    // is no longer valid once we destroy it below. Drop it here so the next
    // submit_grid_frame falls back to clear-color until JNI re-initializes.
    // Java side observes the `static_grid_dropped` log line and may re-init.
    if let Some(grid) = s.static_grid.take() {
        log::warn!(target: "WarpStaticGrid",
            "static_grid_dropped reason=swapchain_recreate atlas_glyphs={} instances={}",
            grid.atlas_glyph_count, grid.glyphs_per_frame);
        grid.destroy(&s.device);
    }
    // M3-S08: same lifecycle rule as static_grid for the per-cell dynamic
    // grid pipeline. Logged so the test driver can detect mid-capture
    // orientation/scale events.
    if let Some(grid) = s.dynamic_grid.take() {
        log::warn!(target: "WarpDynamicGrid",
            "dynamic_grid_dropped reason=swapchain_recreate atlas_glyphs={} instances={} bg_quads={}",
            grid.atlas_glyph_count, grid.glyphs_per_frame, grid.bg_quads_per_frame);
        grid.destroy(&s.device);
    }

    for fb in s.framebuffers.drain(..) {
        s.device.destroy_framebuffer(fb, None);
    }
    for iv in s.image_views.drain(..) {
        s.device.destroy_image_view(iv, None);
    }
    // Round-3 (Codex round-2 blocker 2): tear down old per-image present-wait
    // semaphores BEFORE creating new ones (image count can change across
    // recreate, e.g. driver picks a different min_image_count post-rotation).
    for sem in s.render_finished_per_image.drain(..) {
        s.device.destroy_semaphore(sem, None);
    }
    s.device.destroy_render_pass(s.render_pass, None);
    s.device.free_command_buffers(s.command_pool, &s.command_buffers);
    s.device.destroy_command_pool(s.command_pool, None);

    let old = s.swapchain;
    let (
        swapchain,
        format,
        extent,
        render_pass,
        image_views,
        framebuffers,
        swapchain_images,
        swapchain_supports_transfer_src,
        command_pool,
        command_buffers,
    ) = create_swapchain_and_dependents(
        &s.surface_loader,
        s.phys_device,
        s.queue_family,
        s.surface,
        &s.device,
        &s.swapchain_loader,
        old,
    )?;
    s.swapchain_loader.destroy_swapchain(old, None);

    // Allocate fresh per-image semaphores matching the new image count.
    let sem_info = vk::SemaphoreCreateInfo::default();
    let mut render_finished_per_image = Vec::with_capacity(framebuffers.len());
    for _ in 0..framebuffers.len() {
        let sem = s.device.create_semaphore(&sem_info, None)?;
        render_finished_per_image.push(sem);
    }

    s.swapchain = swapchain;
    s.surface_format = format;
    s.extent = extent;
    s.render_pass = render_pass;
    s.image_views = image_views;
    s.framebuffers = framebuffers;
    s.swapchain_images = swapchain_images;
    s.swapchain_supports_transfer_src = swapchain_supports_transfer_src;
    s.command_pool = command_pool;
    s.command_buffers = command_buffers;
    s.render_finished_per_image = render_finished_per_image;

    let t1 = uptime_millis();
    log::info!(target: "WarpVulkan",
        "recreate_swapchain ok dt_ms={} extent={}x{}",
        t1 - t0, extent.width, extent.height);
    Ok(())
}

/// Outcome of one frame's record-submit-present cycle.
///
/// Round-2 fix (Codex blockers 1+2): in ash 0.38, `acquire_next_image` and
/// `queue_present` return `Ok((idx, suboptimal))` and `Ok(suboptimal)` — the
/// suboptimal bool is NOT folded into `Err(SUBOPTIMAL_KHR)`. Plan Amendment 2
/// SUBOPTIMAL_KHR handling requires us to extract that bool and recreate the
/// swapchain BEFORE the next frame.
///
/// Refs:
///   <https://docs.rs/ash/latest/ash/khr/swapchain/struct.Device.html>
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum FrameOutcome {
    /// Present succeeded; swapchain still optimal — keep going.
    Presented,
    /// Present succeeded BUT swapchain is suboptimal (e.g. orientation changed
    /// mid-frame). Recreate before the next frame.
    PresentedSuboptimal,
    /// Present or acquire returned OUT_OF_DATE; swapchain must be recreated.
    OutOfDate,
}

unsafe fn record_and_present_clear(
    s: &mut VulkanSurface,
    clear_rgba: [f32; 4],
) -> Result<FrameOutcome, vk::Result> {
    let device = &s.device;

    // ── Acquire ─────────────────────────────────────────────────────────────
    // ash 0.38: returns Ok((u32, bool)) where the bool is `suboptimal`.
    let (image_index, acquire_suboptimal) = match s.swapchain_loader.acquire_next_image(
        s.swapchain,
        u64::MAX,
        s.image_available,
        vk::Fence::null(),
    ) {
        Ok(pair) => pair,
        Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => {
            // No present happened — fence/cmd-pool are still clean from prior
            // frame. Return early; caller will recreate.
            return Ok(FrameOutcome::OutOfDate);
        }
        Err(e) => return Err(e),
    };

    let cmd_buf = s.command_buffers[image_index as usize];
    let begin_info = vk::CommandBufferBeginInfo::default()
        .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT);
    device.begin_command_buffer(cmd_buf, &begin_info)?;

    let clear_values = [vk::ClearValue {
        color: vk::ClearColorValue {
            float32: clear_rgba,
        },
    }];
    let rp_begin = vk::RenderPassBeginInfo::default()
        .render_pass(s.render_pass)
        .framebuffer(s.framebuffers[image_index as usize])
        .render_area(vk::Rect2D {
            offset: vk::Offset2D::default(),
            extent: s.extent,
        })
        .clear_values(&clear_values);
    device.cmd_begin_render_pass(cmd_buf, &rp_begin, vk::SubpassContents::INLINE);
    device.cmd_end_render_pass(cmd_buf);
    device.end_command_buffer(cmd_buf)?;

    // ── Submit ──────────────────────────────────────────────────────────────
    // Round-3 (Codex round-2 blocker 2): per-image present-wait semaphore.
    // <https://docs.vulkan.org/guide/latest/swapchain_semaphore_reuse.html>
    let wait_semaphores = [s.image_available];
    let signal_semaphores = [s.render_finished_per_image[image_index as usize]];
    let wait_stages = [vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT];
    let cmd_bufs = [cmd_buf];
    let submit_info = vk::SubmitInfo::default()
        .wait_semaphores(&wait_semaphores)
        .wait_dst_stage_mask(&wait_stages)
        .command_buffers(&cmd_bufs)
        .signal_semaphores(&signal_semaphores);
    device.queue_submit(s.graphics_queue, &[submit_info], s.fence)?;

    // ── Present ─────────────────────────────────────────────────────────────
    // ash 0.38: queue_present returns Ok(suboptimal: bool) on success, or
    // Err(ERROR_OUT_OF_DATE_KHR) on swapchain mismatch.
    let swapchains = [s.swapchain];
    let image_indices = [image_index];
    let present_info = vk::PresentInfoKHR::default()
        .wait_semaphores(&signal_semaphores)
        .swapchains(&swapchains)
        .image_indices(&image_indices);
    let present_result = s
        .swapchain_loader
        .queue_present(s.graphics_queue, &present_info);

    // Round-2 fix (Codex blocker 2): on present-error path, the submit
    // already occurred so the fence is signaled and the command pool is
    // dirty. We MUST drain the queue + reset fence + reset command pool
    // before returning, otherwise the next submit (post-recreate) reuses a
    // signaled fence → undefined behavior.
    let present_suboptimal = match present_result {
        Ok(suboptimal) => suboptimal,
        Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => {
            // Drain + reset before recreate.
            device.queue_wait_idle(s.graphics_queue)?;
            device.reset_fences(&[s.fence])?;
            device.reset_command_pool(s.command_pool, vk::CommandPoolResetFlags::empty())?;
            return Ok(FrameOutcome::OutOfDate);
        }
        Err(e) => {
            // Same cleanup as above so the next attempt has a clean state.
            // Errors other than OUT_OF_DATE are unrecoverable for this frame
            // but should not poison subsequent attempts.
            let _ = device.queue_wait_idle(s.graphics_queue);
            let _ = device.reset_fences(&[s.fence]);
            let _ = device.reset_command_pool(s.command_pool, vk::CommandPoolResetFlags::empty());
            return Err(e);
        }
    };

    // Successful present path — drain the queue, reset fence + pool for the
    // next frame.
    device.queue_wait_idle(s.graphics_queue)?;
    device.reset_fences(&[s.fence])?;
    device.reset_command_pool(s.command_pool, vk::CommandPoolResetFlags::empty())?;

    if acquire_suboptimal || present_suboptimal {
        Ok(FrameOutcome::PresentedSuboptimal)
    } else {
        Ok(FrameOutcome::Presented)
    }
}

// ---------------------------------------------------------------------------
// M2-S05: frame capture (vkCmdCopyImageToBuffer + PNG encode)
//
// Mirrors the canonical impl in
//   warp-src/crates/warpui/src/platform/android/vulkan.rs
// Both follow the SaschaWillems screenshot.cpp readback pattern.
// ---------------------------------------------------------------------------

/// Metadata returned by a successful capture.
#[derive(Debug, Clone, Copy)]
pub(crate) struct CaptureMetadata {
    pub width: u32,
    pub height: u32,
    pub mean_r: u8,
    pub mean_g: u8,
    pub mean_b: u8,
    pub png_bytes_written: u64,
    pub bgra_swizzled: bool,
}

fn find_memory_type(
    instance: &ash::Instance,
    phys_device: vk::PhysicalDevice,
    memory_type_bits: u32,
    properties: vk::MemoryPropertyFlags,
) -> Option<u32> {
    // SAFETY: phys_device is owned by `instance`.
    let mem_props = unsafe { instance.get_physical_device_memory_properties(phys_device) };
    for i in 0..mem_props.memory_type_count {
        let suitable = (memory_type_bits & (1 << i)) != 0;
        let supported = mem_props.memory_types[i as usize]
            .property_flags
            .contains(properties);
        if suitable && supported {
            return Some(i);
        }
    }
    None
}

// The inner variant fields are intentionally read only via Debug formatting
// in error logs; the dead_code lint can't see through {:?} usage.
#[allow(dead_code)]
#[derive(Debug)]
enum CaptureError {
    Vk(vk::Result),
    Io(std::io::Error),
    Png(png::EncodingError),
    Encode(String),
}

impl From<vk::Result> for CaptureError {
    fn from(e: vk::Result) -> Self {
        CaptureError::Vk(e)
    }
}
impl From<std::io::Error> for CaptureError {
    fn from(e: std::io::Error) -> Self {
        CaptureError::Io(e)
    }
}
impl From<png::EncodingError> for CaptureError {
    fn from(e: png::EncodingError) -> Self {
        CaptureError::Png(e)
    }
}

/// Captured pixel data + metadata returned from `record_and_capture_raw`.
struct CapturedRgba {
    rgba: Vec<u8>,
    width: u32,
    height: u32,
    mean_r: u8,
    mean_g: u8,
    mean_b: u8,
    bgra_swizzled: bool,
}

/// Round-2 capture pipeline — mirror of canonical impl in
/// `warp-src/crates/warpui/src/platform/android/vulkan.rs::record_and_capture_raw`.
///
/// Codex round-1 blockers fixed:
/// 1. TRANSFER_WRITE → HOST_READ buffer memory barrier before vkMapMemory
///    (HOST_COHERENT removes invalidate, NOT host-visibility).
///    <https://docs.vulkan.org/spec/latest/chapters/synchronization.html>
/// 2. queue_present_khr after submit so WSI recovers the acquired image
///    (otherwise vkAcquireNextImageKHR runs out after 2-3 captures).
///    <https://docs.vulkan.org/spec/latest/chapters/VK_KHR_surface/wsi.html>
#[allow(clippy::too_many_lines)]
unsafe fn record_and_capture_raw(
    s: &mut VulkanSurface,
    clear_rgba: [f32; 4],
) -> Result<CapturedRgba, CaptureError> {
    let device = &s.device;
    let extent = s.extent;
    let buffer_size: vk::DeviceSize = (extent.width as u64) * (extent.height as u64) * 4;

    let (image_index, _suboptimal) = match s.swapchain_loader.acquire_next_image(
        s.swapchain,
        u64::MAX,
        s.image_available,
        vk::Fence::null(),
    ) {
        Ok(pair) => pair,
        Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => {
            log::warn!(target: "WarpVulkan", "capture: acquire returned OUT_OF_DATE");
            return Err(CaptureError::Vk(vk::Result::ERROR_OUT_OF_DATE_KHR));
        }
        Err(e) => return Err(e.into()),
    };
    let target_image = s.swapchain_images[image_index as usize];

    // Staging buffer — host-coherent so we don't need vkInvalidateMappedMemoryRanges.
    let buffer_info = vk::BufferCreateInfo::default()
        .size(buffer_size)
        .usage(vk::BufferUsageFlags::TRANSFER_DST)
        .sharing_mode(vk::SharingMode::EXCLUSIVE);
    let staging_buffer = device.create_buffer(&buffer_info, None)?;

    let mem_req = device.get_buffer_memory_requirements(staging_buffer);
    let mem_type = find_memory_type(
        &s.instance,
        s.phys_device,
        mem_req.memory_type_bits,
        vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT,
    )
    .ok_or_else(|| {
        device.destroy_buffer(staging_buffer, None);
        CaptureError::Encode("no HOST_VISIBLE | HOST_COHERENT memory type".into())
    })?;

    let alloc_info = vk::MemoryAllocateInfo::default()
        .allocation_size(mem_req.size)
        .memory_type_index(mem_type);
    let staging_memory = match device.allocate_memory(&alloc_info, None) {
        Ok(m) => m,
        Err(e) => {
            device.destroy_buffer(staging_buffer, None);
            return Err(e.into());
        }
    };
    if let Err(e) = device.bind_buffer_memory(staging_buffer, staging_memory, 0) {
        device.free_memory(staging_memory, None);
        device.destroy_buffer(staging_buffer, None);
        return Err(e.into());
    }

    let cmd_buf = s.command_buffers[image_index as usize];
    let begin_info = vk::CommandBufferBeginInfo::default()
        .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT);
    if let Err(e) = device.begin_command_buffer(cmd_buf, &begin_info) {
        device.free_memory(staging_memory, None);
        device.destroy_buffer(staging_buffer, None);
        return Err(e.into());
    }

    // Render-pass clear so the image carries the magenta color.
    let clear_values = [vk::ClearValue {
        color: vk::ClearColorValue {
            float32: clear_rgba,
        },
    }];
    let rp_begin = vk::RenderPassBeginInfo::default()
        .render_pass(s.render_pass)
        .framebuffer(s.framebuffers[image_index as usize])
        .render_area(vk::Rect2D {
            offset: vk::Offset2D::default(),
            extent: s.extent,
        })
        .clear_values(&clear_values);
    device.cmd_begin_render_pass(cmd_buf, &rp_begin, vk::SubpassContents::INLINE);
    device.cmd_end_render_pass(cmd_buf);

    // PRESENT_SRC_KHR → TRANSFER_SRC_OPTIMAL.
    let to_transfer_src = vk::ImageMemoryBarrier::default()
        .src_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE)
        .dst_access_mask(vk::AccessFlags::TRANSFER_READ)
        .old_layout(vk::ImageLayout::PRESENT_SRC_KHR)
        .new_layout(vk::ImageLayout::TRANSFER_SRC_OPTIMAL)
        .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
        .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
        .image(target_image)
        .subresource_range(
            vk::ImageSubresourceRange::default()
                .aspect_mask(vk::ImageAspectFlags::COLOR)
                .base_mip_level(0)
                .level_count(1)
                .base_array_layer(0)
                .layer_count(1),
        );
    device.cmd_pipeline_barrier(
        cmd_buf,
        vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT,
        vk::PipelineStageFlags::TRANSFER,
        vk::DependencyFlags::empty(),
        &[],
        &[],
        &[to_transfer_src],
    );

    // Copy image → buffer.
    let region = vk::BufferImageCopy::default()
        .buffer_offset(0)
        .buffer_row_length(0)
        .buffer_image_height(0)
        .image_subresource(
            vk::ImageSubresourceLayers::default()
                .aspect_mask(vk::ImageAspectFlags::COLOR)
                .mip_level(0)
                .base_array_layer(0)
                .layer_count(1),
        )
        .image_offset(vk::Offset3D::default())
        .image_extent(vk::Extent3D {
            width: extent.width,
            height: extent.height,
            depth: 1,
        });
    device.cmd_copy_image_to_buffer(
        cmd_buf,
        target_image,
        vk::ImageLayout::TRANSFER_SRC_OPTIMAL,
        staging_buffer,
        &[region],
    );

    // Round-2 (Codex round-1 blocker 1): TRANSFER_WRITE → HOST_READ buffer
    // memory barrier on the staging buffer. HOST_COHERENT removes invalidate,
    // NOT the host-visibility barrier. Mirror of warp-src canonical impl.
    let to_host_read = vk::BufferMemoryBarrier::default()
        .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
        .dst_access_mask(vk::AccessFlags::HOST_READ)
        .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
        .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
        .buffer(staging_buffer)
        .offset(0)
        .size(vk::WHOLE_SIZE);
    device.cmd_pipeline_barrier(
        cmd_buf,
        vk::PipelineStageFlags::TRANSFER,
        vk::PipelineStageFlags::HOST,
        vk::DependencyFlags::empty(),
        &[],
        &[to_host_read],
        &[],
    );

    // TRANSFER_SRC_OPTIMAL → PRESENT_SRC_KHR (so we can call queue_present
    // below — required to release the acquired image back to WSI per
    // <https://docs.vulkan.org/spec/latest/chapters/VK_KHR_surface/wsi.html>).
    let to_present_src = vk::ImageMemoryBarrier::default()
        .src_access_mask(vk::AccessFlags::TRANSFER_READ)
        .dst_access_mask(vk::AccessFlags::empty())
        .old_layout(vk::ImageLayout::TRANSFER_SRC_OPTIMAL)
        .new_layout(vk::ImageLayout::PRESENT_SRC_KHR)
        .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
        .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
        .image(target_image)
        .subresource_range(
            vk::ImageSubresourceRange::default()
                .aspect_mask(vk::ImageAspectFlags::COLOR)
                .base_mip_level(0)
                .level_count(1)
                .base_array_layer(0)
                .layer_count(1),
        );
    device.cmd_pipeline_barrier(
        cmd_buf,
        vk::PipelineStageFlags::TRANSFER,
        vk::PipelineStageFlags::BOTTOM_OF_PIPE,
        vk::DependencyFlags::empty(),
        &[],
        &[],
        &[to_present_src],
    );

    if let Err(e) = device.end_command_buffer(cmd_buf) {
        device.free_memory(staging_memory, None);
        device.destroy_buffer(staging_buffer, None);
        return Err(e.into());
    }

    // Round-3 (Codex round-2 blocker 2): per-image present-wait semaphore.
    // queue_present_khr waits on the same per-image semaphore. Refs:
    //   <https://docs.vulkan.org/guide/latest/swapchain_semaphore_reuse.html>
    let wait_semaphores = [s.image_available];
    let signal_semaphores = [s.render_finished_per_image[image_index as usize]];
    let wait_stages = [vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT];
    let cmd_bufs = [cmd_buf];
    let submit_info = vk::SubmitInfo::default()
        .wait_semaphores(&wait_semaphores)
        .wait_dst_stage_mask(&wait_stages)
        .command_buffers(&cmd_bufs)
        .signal_semaphores(&signal_semaphores);
    if let Err(e) = device.queue_submit(s.graphics_queue, &[submit_info], s.fence) {
        device.free_memory(staging_memory, None);
        device.destroy_buffer(staging_buffer, None);
        return Err(e.into());
    }

    // Present the captured image so WSI bookkeeping recovers it.
    let swapchains = [s.swapchain];
    let image_indices = [image_index];
    let present_info = vk::PresentInfoKHR::default()
        .wait_semaphores(&signal_semaphores)
        .swapchains(&swapchains)
        .image_indices(&image_indices);
    let present_result = s
        .swapchain_loader
        .queue_present(s.graphics_queue, &present_info);

    if let Err(e) = device.queue_wait_idle(s.graphics_queue) {
        device.free_memory(staging_memory, None);
        device.destroy_buffer(staging_buffer, None);
        return Err(e.into());
    }
    let _ = device.reset_fences(&[s.fence]);
    let _ = device.reset_command_pool(s.command_pool, vk::CommandPoolResetFlags::empty());

    match present_result {
        Ok(false) => {} // optimal
        Ok(true) => {
            log::warn!(target: "WarpVulkan",
                "capture: present returned SUBOPTIMAL (caller will recreate)");
        }
        Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => {
            log::warn!(target: "WarpVulkan",
                "capture: present returned OUT_OF_DATE (caller will recreate)");
        }
        Err(e) => {
            log::error!(target: "WarpVulkan",
                "capture: queue_present unexpected error: {:?}", e);
            // Don't fail the capture — readback already succeeded.
        }
    }

    // Map memory + extract pixels.
    let raw_ptr = match device.map_memory(
        staging_memory,
        0,
        buffer_size,
        vk::MemoryMapFlags::empty(),
    ) {
        Ok(p) => p as *const u8,
        Err(e) => {
            device.free_memory(staging_memory, None);
            device.destroy_buffer(staging_buffer, None);
            return Err(e.into());
        }
    };
    let pixels: &[u8] = std::slice::from_raw_parts(raw_ptr, buffer_size as usize);

    let bgra_swizzled = matches!(
        s.surface_format,
        vk::Format::B8G8R8A8_UNORM | vk::Format::B8G8R8A8_SRGB
    );

    let mut rgba = Vec::with_capacity(buffer_size as usize);
    if bgra_swizzled {
        for chunk in pixels.chunks_exact(4) {
            rgba.push(chunk[2]);
            rgba.push(chunk[1]);
            rgba.push(chunk[0]);
            rgba.push(chunk[3]);
        }
    } else {
        rgba.extend_from_slice(pixels);
    }

    device.unmap_memory(staging_memory);

    let (mut sum_r, mut sum_g, mut sum_b): (u64, u64, u64) = (0, 0, 0);
    let pixel_count = (extent.width as u64) * (extent.height as u64);
    for chunk in rgba.chunks_exact(4) {
        sum_r += chunk[0] as u64;
        sum_g += chunk[1] as u64;
        sum_b += chunk[2] as u64;
    }
    let mean_r = (sum_r / pixel_count.max(1)) as u8;
    let mean_g = (sum_g / pixel_count.max(1)) as u8;
    let mean_b = (sum_b / pixel_count.max(1)) as u8;

    device.free_memory(staging_memory, None);
    device.destroy_buffer(staging_buffer, None);

    Ok(CapturedRgba {
        rgba,
        width: extent.width,
        height: extent.height,
        mean_r,
        mean_g,
        mean_b,
        bgra_swizzled,
    })
}

/// PNG-encoding wrapper around [`record_and_capture_raw`]. Used by the
/// device-driver test path (JNI `renderCaptureFrame` → `capture_to_png`).
unsafe fn record_and_capture(
    s: &mut VulkanSurface,
    clear_rgba: [f32; 4],
    out_path: &std::path::Path,
) -> Result<CaptureMetadata, CaptureError> {
    let captured = record_and_capture_raw(s, clear_rgba)?;

    if let Some(parent) = out_path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    let file = std::fs::File::create(out_path)?;
    let writer = std::io::BufWriter::new(file);
    let mut encoder = png::Encoder::new(writer, captured.width, captured.height);
    encoder.set_color(png::ColorType::Rgba);
    encoder.set_depth(png::BitDepth::Eight);
    let mut writer = encoder.write_header()?;
    writer.write_image_data(&captured.rgba)?;
    writer.finish()?;

    let png_bytes_written = std::fs::metadata(out_path).map(|m| m.len()).unwrap_or(0);

    Ok(CaptureMetadata {
        width: captured.width,
        height: captured.height,
        mean_r: captured.mean_r,
        mean_g: captured.mean_g,
        mean_b: captured.mean_b,
        png_bytes_written,
        bgra_swizzled: captured.bgra_swizzled,
    })
}

/// Public capture entry — JNI calls this from `renderCaptureFrame`.
///
/// Returns `Some(meta)` on success or `None` if no surface attached / capture
/// path failed (see logcat WarpVulkan tag for diagnosis).
pub(crate) fn capture_to_png(
    clear_rgba: [f32; 4],
    out_path: &std::path::Path,
) -> Option<CaptureMetadata> {
    let mut state = match SURFACE_STATE.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    let Some(s) = state.as_mut() else {
        log::error!(target: "WarpVulkan", "capture_to_png: no surface attached");
        return None;
    };
    if !s.swapchain_supports_transfer_src {
        log::error!(target: "WarpVulkan",
            "capture_to_png: swapchain does not advertise TRANSFER_SRC; cannot read back");
        return None;
    }
    match unsafe { record_and_capture(s, clear_rgba, out_path) } {
        Ok(meta) => {
            let n = if let Ok(mut c) = FRAME_COUNTER.lock() {
                *c += 1;
                *c
            } else {
                0
            };
            log::info!(target: "WarpVulkan",
                "capture_ok frame={} ts={} dims={}x{} bytes={} mean_rgb={},{},{} bgra_swizzled={}",
                n, uptime_millis(), meta.width, meta.height,
                meta.png_bytes_written, meta.mean_r, meta.mean_g, meta.mean_b,
                meta.bgra_swizzled);
            Some(meta)
        }
        Err(e) => {
            log::error!(target: "WarpVulkan", "capture_to_png failed: {:?}", e);
            None
        }
    }
}

// ---------------------------------------------------------------------------
// M2-S07: capture + font composite path
// ---------------------------------------------------------------------------

/// Metadata returned by [`capture_to_png_with_text`].
///
/// In addition to the M2-S05 capture metadata, this carries the M2-S07 font-
/// render counters (fonts loaded, glyphs shaped, pixels touched). The driver
/// script greps for the `font_render_ok` line to verify glyph coverage on
/// device. Fields are read via Debug formatting in error logs and surfaced to
/// callers indirectly through the `font_render_ok` logcat line; the
/// dead-code lint can't see through `{:?}` usage.
#[allow(dead_code)]
#[derive(Debug, Clone)]
pub(crate) struct CaptureWithTextMetadata {
    pub width: u32,
    pub height: u32,
    pub mean_r_before: u8,
    pub mean_g_before: u8,
    pub mean_b_before: u8,
    pub mean_r_after: u8,
    pub mean_g_after: u8,
    pub mean_b_after: u8,
    pub png_bytes_written: u64,
    pub bgra_swizzled: bool,
    pub font_via: String,
    pub fonts_loaded: usize,
    pub families_loaded: usize,
    pub glyphs_total: usize,
    pub glyphs_missing: usize,
    pub composed_pixels: u64,
}

/// Compute mean RGB across an RGBA buffer.
fn mean_rgb(rgba: &[u8], width: u32, height: u32) -> (u8, u8, u8) {
    let pixel_count = (width as u64) * (height as u64);
    if pixel_count == 0 || rgba.is_empty() {
        return (0, 0, 0);
    }
    let mut sr: u64 = 0;
    let mut sg: u64 = 0;
    let mut sb: u64 = 0;
    for chunk in rgba.chunks_exact(4) {
        sr += chunk[0] as u64;
        sg += chunk[1] as u64;
        sb += chunk[2] as u64;
    }
    (
        (sr / pixel_count) as u8,
        (sg / pixel_count) as u8,
        (sb / pixel_count) as u8,
    )
}

/// Capture + composite text + PNG-encode in one shot.
///
/// 1. Grabs the swapchain image as RGBA via the M2-S05 readback path.
/// 2. Calls `font_render::compose_text_on_rgba` to shape `text` and overlay
///    the resulting glyphs onto the captured buffer at `(baseline_x, baseline_y)`
///    using `font_size_px`.
/// 3. Encodes the modified buffer as PNG.
///
/// Returns `Some(meta)` on success, including the before/after mean-RGB delta
/// + glyph counts so the test driver can assert glyph pixels exist.
pub(crate) fn capture_to_png_with_text(
    clear_rgba: [f32; 4],
    out_path: &std::path::Path,
    text: &str,
    font_size_px: f32,
    baseline_x: f32,
    baseline_y: f32,
) -> Option<CaptureWithTextMetadata> {
    let mut state = match SURFACE_STATE.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    let Some(s) = state.as_mut() else {
        log::error!(
            target: "WarpVulkan",
            "capture_to_png_with_text: no surface attached"
        );
        return None;
    };
    if !s.swapchain_supports_transfer_src {
        log::error!(
            target: "WarpVulkan",
            "capture_to_png_with_text: swapchain does not advertise TRANSFER_SRC"
        );
        return None;
    }
    let captured = match unsafe { record_and_capture_raw(s, clear_rgba) } {
        Ok(c) => c,
        Err(e) => {
            log::error!(
                target: "WarpVulkan",
                "capture_to_png_with_text: capture failed: {:?}",
                e
            );
            return None;
        }
    };
    let CapturedRgba {
        mut rgba,
        width,
        height,
        mean_r,
        mean_g,
        mean_b,
        bgra_swizzled,
    } = captured;

    // Composite text onto the captured RGBA buffer.
    let stats = crate::font_render::compose_text_on_rgba(
        rgba.as_mut_slice(),
        width,
        height,
        text,
        font_size_px,
        baseline_x,
        baseline_y,
    );

    // Mean RGB after composite (for the driver's drift assertion).
    let (after_r, after_g, after_b) = mean_rgb(&rgba, width, height);

    // Encode PNG.
    if let Some(parent) = out_path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    let file = match std::fs::File::create(out_path) {
        Ok(f) => f,
        Err(e) => {
            log::error!(
                target: "WarpVulkan",
                "capture_to_png_with_text: create PNG file failed: {:?}",
                e
            );
            return None;
        }
    };
    let writer = std::io::BufWriter::new(file);
    let mut encoder = png::Encoder::new(writer, width, height);
    encoder.set_color(png::ColorType::Rgba);
    encoder.set_depth(png::BitDepth::Eight);
    let mut writer = match encoder.write_header() {
        Ok(w) => w,
        Err(e) => {
            log::error!(
                target: "WarpVulkan",
                "capture_to_png_with_text: PNG header write failed: {:?}",
                e
            );
            return None;
        }
    };
    if let Err(e) = writer.write_image_data(&rgba) {
        log::error!(
            target: "WarpVulkan",
            "capture_to_png_with_text: PNG image data write failed: {:?}",
            e
        );
        return None;
    }
    if let Err(e) = writer.finish() {
        log::error!(
            target: "WarpVulkan",
            "capture_to_png_with_text: PNG finish failed: {:?}",
            e
        );
        return None;
    }
    let png_bytes_written = std::fs::metadata(out_path).map(|m| m.len()).unwrap_or(0);

    let n = if let Ok(mut c) = FRAME_COUNTER.lock() {
        *c += 1;
        *c
    } else {
        0
    };
    log::info!(
        target: "WarpVulkan",
        "capture_ok frame={} ts={} dims={}x{} bytes={} mean_rgb={},{},{} bgra_swizzled={}",
        n, uptime_millis(), width, height,
        png_bytes_written, mean_r, mean_g, mean_b, bgra_swizzled
    );
    log::info!(
        target: "WarpVulkan",
        "font_render_ok via={} fonts_loaded={} families_loaded={} glyphs_total={} glyphs_missing={} composed_pixels={} mean_rgb_after={},{},{} primary_family={:?} cjk_family={:?}",
        stats.via,
        stats.fonts_loaded,
        stats.families_loaded,
        stats.glyphs_total,
        stats.glyphs_missing,
        stats.composed_pixels,
        after_r, after_g, after_b,
        stats.primary_family,
        stats.cjk_family
    );

    Some(CaptureWithTextMetadata {
        width,
        height,
        mean_r_before: mean_r,
        mean_g_before: mean_g,
        mean_b_before: mean_b,
        mean_r_after: after_r,
        mean_g_after: after_g,
        mean_b_after: after_b,
        png_bytes_written,
        bgra_swizzled,
        font_via: stats.via.to_string(),
        fonts_loaded: stats.fonts_loaded,
        families_loaded: stats.families_loaded,
        glyphs_total: stats.glyphs_total,
        glyphs_missing: stats.glyphs_missing,
        composed_pixels: stats.composed_pixels,
    })
}

fn destroy_surface(mut s: VulkanSurface) {
    // SAFETY: ordered RAII teardown — wait_idle, per-surface, device, instance, native_window.
    unsafe {
        let _ = s.device.device_wait_idle();
        // M2-S08: tear down the static-grid pipeline + atlas BEFORE the
        // device, so its image/buffer/pipeline handles are valid at destroy
        // time.
        if let Some(grid) = s.static_grid.take() {
            grid.destroy(&s.device);
        }
        // M3-S08: tear down the dynamic-grid pipeline + atlas BEFORE the
        // device.
        if let Some(grid) = s.dynamic_grid.take() {
            grid.destroy(&s.device);
        }
        for fb in &s.framebuffers {
            s.device.destroy_framebuffer(*fb, None);
        }
        for iv in &s.image_views {
            s.device.destroy_image_view(*iv, None);
        }
        s.device.destroy_semaphore(s.image_available, None);
        // Round-3 (Codex round-2 blocker 2): destroy ALL per-image present-wait
        // semaphores.
        for sem in &s.render_finished_per_image {
            s.device.destroy_semaphore(*sem, None);
        }
        s.device.destroy_fence(s.fence, None);
        s.device.destroy_command_pool(s.command_pool, None);
        s.swapchain_loader.destroy_swapchain(s.swapchain, None);
        s.device.destroy_render_pass(s.render_pass, None);
        s.device.destroy_device(None);
        s.surface_loader.destroy_surface(s.surface, None);
        if let Some(loader) = &s.debug_utils_loader {
            if s.debug_messenger != vk::DebugUtilsMessengerEXT::null() {
                loader.destroy_debug_utils_messenger(s.debug_messenger, None);
            }
        }
        s.instance.destroy_instance(None);
        ndk_sys::ANativeWindow_release(s.native_window);
        let _ = s.entry;
    }
}

// ---------------------------------------------------------------------------
// Public-ish API used by JNI exports
// ---------------------------------------------------------------------------

/// Attaches an `ANativeWindow*` (typically obtained via
/// `ANativeWindow_fromSurface`).
///
/// # Safety
///
/// `native_window` must be a valid pointer with one outstanding ref count
/// owned by the caller. Ownership transfers to the swapchain (released on
/// detach).
pub(crate) unsafe fn attach(native_window: *mut ndk_sys::ANativeWindow) -> bool {
    if native_window.is_null() {
        log::error!(target: "WarpVulkan", "attach: null native_window");
        return false;
    }
    let mut state = match SURFACE_STATE.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    if let Some(old) = state.take() {
        log::info!(target: "WarpVulkan", "replacing prior VulkanSurface");
        destroy_surface(old);
    }
    match init_surface(native_window) {
        Ok(s) => {
            log::info!(target: "WarpVulkan",
                "attach ok extent={}x{} images={}",
                s.extent.width, s.extent.height, s.framebuffers.len());
            *state = Some(s);
            // Reset frame counter for the new attach.
            if let Ok(mut c) = FRAME_COUNTER.lock() {
                *c = 0;
            }
            true
        }
        Err(e) => {
            log::error!(target: "WarpVulkan", "init_surface failed: {:?}", e);
            ndk_sys::ANativeWindow_release(native_window);
            false
        }
    }
}

pub(crate) fn detach() {
    let mut state = match SURFACE_STATE.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    if let Some(s) = state.take() {
        destroy_surface(s);
        log::info!(target: "WarpVulkan", "detach ok");
    }
}

/// Submits a single clear-color frame. Returns `true` on successful present.
pub(crate) fn submit_clear_frame(clear_rgba: [f32; 4]) -> bool {
    let mut state = match SURFACE_STATE.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    let Some(s) = state.as_mut() else {
        return false;
    };
    match unsafe { record_and_present_clear(s, clear_rgba) } {
        Ok(FrameOutcome::Presented) => {
            let n = if let Ok(mut c) = FRAME_COUNTER.lock() {
                *c += 1;
                *c
            } else {
                0
            };
            let ts = uptime_millis();
            // Tag parsed by tools/scripts/test-render-scene.sh: "present_ok frame=<n> ts=<ms>".
            log::info!(target: "WarpVulkan", "present_ok frame={} ts={}", n, ts);
            true
        }
        Ok(FrameOutcome::PresentedSuboptimal) => {
            // Round-2 (Codex blocker 1): swapchain reports suboptimal but the
            // present succeeded. Count the frame, then schedule a recreate
            // before the next acquire so the new orientation/scale takes
            // effect cleanly on the next vsync.
            let n = if let Ok(mut c) = FRAME_COUNTER.lock() {
                *c += 1;
                *c
            } else {
                0
            };
            let ts = uptime_millis();
            log::info!(target: "WarpVulkan", "present_ok frame={} ts={}", n, ts);
            log::warn!(target: "WarpVulkan", "swapchain suboptimal — recreating before next frame");
            if let Err(e) = unsafe { recreate_swapchain(s) } {
                log::error!(target: "WarpVulkan", "recreate after suboptimal failed: {:?}", e);
            }
            true
        }
        Ok(FrameOutcome::OutOfDate) => {
            log::warn!(target: "WarpVulkan", "swapchain out-of-date — recreating");
            if let Err(e) = unsafe { recreate_swapchain(s) } {
                log::error!(target: "WarpVulkan", "recreate failed: {:?}", e);
            }
            false
        }
        Err(e) => {
            log::error!(target: "WarpVulkan", "present failed: {:?}", e);
            false
        }
    }
}

#[allow(dead_code)]
pub(crate) fn frames_presented() -> u64 {
    FRAME_COUNTER.lock().map(|g| *g).unwrap_or(0)
}

// ---------------------------------------------------------------------------
// M2-S08: static glyph grid render path
// ---------------------------------------------------------------------------

/// Initializes the static glyph grid renderer. Builds the atlas + per-instance
/// vertex buffer + pipeline once; subsequent `submit_grid_frame` calls reuse
/// these resources at zero per-frame allocation cost.
///
/// Returns true on success. Logs `static_grid_init_ok` with diagnostic
/// counters; the test driver greps for these.
///
/// Idempotent: if a grid is already attached, the old one is destroyed first.
#[allow(clippy::too_many_arguments)]
pub(crate) fn init_static_grid(
    text_per_cell: &str,
    font_size_px: f32,
    rows: u32,
    cols: u32,
    cell_w_px: f32,
    cell_h_px: f32,
) -> bool {
    let mut state = match SURFACE_STATE.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    let Some(s) = state.as_mut() else {
        log::error!(target: "WarpStaticGrid", "init_static_grid: no surface attached");
        return false;
    };
    // SAFETY: device_wait_idle followed by static_grid drop avoids in-flight
    // command buffers referencing the old grid.
    unsafe {
        let _ = s.device.device_wait_idle();
        if let Some(old) = s.static_grid.take() {
            old.destroy(&s.device);
        }
    }
    let t0 = uptime_millis();
    let grid = unsafe {
        crate::static_grid::StaticGrid::new(
            &s.instance,
            &s.device,
            s.phys_device,
            s.graphics_queue,
            s.command_pool,
            s.render_pass,
            text_per_cell,
            font_size_px,
            rows,
            cols,
            cell_w_px,
            cell_h_px,
        )
    };
    match grid {
        Ok(g) => {
            let dt = uptime_millis() - t0;
            log::info!(
                target: "WarpStaticGrid",
                "static_grid_init_ok dt_ms={} text={:?} rows={} cols={} cell={}x{}px font_size_px={} \
                 atlas_glyphs={} instances={}",
                dt, text_per_cell, rows, cols, cell_w_px, cell_h_px, font_size_px,
                g.atlas_glyph_count, g.glyphs_per_frame
            );
            s.static_grid = Some(g);
            true
        }
        Err(e) => {
            log::error!(
                target: "WarpStaticGrid",
                "static_grid_init_fail reason={:?}", e
            );
            false
        }
    }
}

unsafe fn record_and_present_grid(
    s: &mut VulkanSurface,
    clear_rgba: [f32; 4],
) -> Result<FrameOutcome, vk::Result> {
    let device = &s.device;

    let (image_index, acquire_suboptimal) = match s.swapchain_loader.acquire_next_image(
        s.swapchain,
        u64::MAX,
        s.image_available,
        vk::Fence::null(),
    ) {
        Ok(pair) => pair,
        Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => {
            return Ok(FrameOutcome::OutOfDate);
        }
        Err(e) => return Err(e),
    };

    let cmd_buf = s.command_buffers[image_index as usize];
    let begin_info = vk::CommandBufferBeginInfo::default()
        .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT);
    device.begin_command_buffer(cmd_buf, &begin_info)?;

    let clear_values = [vk::ClearValue {
        color: vk::ClearColorValue {
            float32: clear_rgba,
        },
    }];
    let rp_begin = vk::RenderPassBeginInfo::default()
        .render_pass(s.render_pass)
        .framebuffer(s.framebuffers[image_index as usize])
        .render_area(vk::Rect2D {
            offset: vk::Offset2D::default(),
            extent: s.extent,
        })
        .clear_values(&clear_values);
    device.cmd_begin_render_pass(cmd_buf, &rp_begin, vk::SubpassContents::INLINE);
    if let Some(grid) = s.static_grid.as_ref() {
        grid.record_draw(
            device,
            cmd_buf,
            s.extent.width as f32,
            s.extent.height as f32,
        );
    }
    device.cmd_end_render_pass(cmd_buf);
    device.end_command_buffer(cmd_buf)?;

    let wait_semaphores = [s.image_available];
    let signal_semaphores = [s.render_finished_per_image[image_index as usize]];
    let wait_stages = [vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT];
    let cmd_bufs = [cmd_buf];
    let submit_info = vk::SubmitInfo::default()
        .wait_semaphores(&wait_semaphores)
        .wait_dst_stage_mask(&wait_stages)
        .command_buffers(&cmd_bufs)
        .signal_semaphores(&signal_semaphores);
    device.queue_submit(s.graphics_queue, &[submit_info], s.fence)?;

    let swapchains = [s.swapchain];
    let image_indices = [image_index];
    let present_info = vk::PresentInfoKHR::default()
        .wait_semaphores(&signal_semaphores)
        .swapchains(&swapchains)
        .image_indices(&image_indices);
    let present_result = s
        .swapchain_loader
        .queue_present(s.graphics_queue, &present_info);

    let present_suboptimal = match present_result {
        Ok(suboptimal) => suboptimal,
        Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => {
            device.queue_wait_idle(s.graphics_queue)?;
            device.reset_fences(&[s.fence])?;
            device.reset_command_pool(s.command_pool, vk::CommandPoolResetFlags::empty())?;
            return Ok(FrameOutcome::OutOfDate);
        }
        Err(e) => {
            let _ = device.queue_wait_idle(s.graphics_queue);
            let _ = device.reset_fences(&[s.fence]);
            let _ = device.reset_command_pool(s.command_pool, vk::CommandPoolResetFlags::empty());
            return Err(e);
        }
    };

    device.queue_wait_idle(s.graphics_queue)?;
    device.reset_fences(&[s.fence])?;
    device.reset_command_pool(s.command_pool, vk::CommandPoolResetFlags::empty())?;

    if acquire_suboptimal || present_suboptimal {
        Ok(FrameOutcome::PresentedSuboptimal)
    } else {
        Ok(FrameOutcome::Presented)
    }
}

/// Submits a single frame consisting of a clear + the static grid draw.
/// Returns `true` on successful present. If no grid is attached, this is
/// equivalent to `submit_clear_frame`.
pub(crate) fn submit_grid_frame(clear_rgba: [f32; 4]) -> bool {
    let mut state = match SURFACE_STATE.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    let Some(s) = state.as_mut() else {
        return false;
    };
    if s.static_grid.is_none() {
        // Defensive: caller asked for grid but it isn't initialized — log
        // ONCE per JNI call (the loop continues, just clears).
        log::debug!(target: "WarpStaticGrid",
            "submit_grid_frame called with no grid attached; falling back to clear");
    }
    match unsafe { record_and_present_grid(s, clear_rgba) } {
        Ok(FrameOutcome::Presented) => {
            let n = if let Ok(mut c) = FRAME_COUNTER.lock() {
                *c += 1;
                *c
            } else {
                0
            };
            let ts = uptime_millis();
            // Same `present_ok` schema as submit_clear_frame so the existing
            // test-render-scene driver can be reused.
            log::info!(target: "WarpVulkan", "present_ok frame={} ts={}", n, ts);
            true
        }
        Ok(FrameOutcome::PresentedSuboptimal) => {
            let n = if let Ok(mut c) = FRAME_COUNTER.lock() {
                *c += 1;
                *c
            } else {
                0
            };
            let ts = uptime_millis();
            log::info!(target: "WarpVulkan", "present_ok frame={} ts={}", n, ts);
            log::warn!(target: "WarpVulkan", "swapchain suboptimal — recreating before next frame");
            if let Err(e) = unsafe { recreate_swapchain(s) } {
                log::error!(target: "WarpVulkan", "recreate after suboptimal failed: {:?}", e);
            }
            true
        }
        Ok(FrameOutcome::OutOfDate) => {
            log::warn!(target: "WarpVulkan", "swapchain out-of-date — recreating");
            if let Err(e) = unsafe { recreate_swapchain(s) } {
                log::error!(target: "WarpVulkan", "recreate failed: {:?}", e);
            }
            false
        }
        Err(e) => {
            log::error!(target: "WarpVulkan", "grid present failed: {:?}", e);
            false
        }
    }
}

/// Returns whether a static grid is currently attached. Used by JNI for
/// diagnostic logging.
#[allow(dead_code)]
pub(crate) fn static_grid_attached() -> bool {
    let state = match SURFACE_STATE.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    state.as_ref().map(|s| s.static_grid.is_some()).unwrap_or(false)
}

/// Returns (atlas_glyph_count, glyphs_per_frame, rows, cols) of the attached
/// grid, if any. Used by the test driver to round-trip diagnostic info into
/// the result JSON.
pub(crate) fn static_grid_stats() -> Option<(u32, u32, u32, u32, String)> {
    let state = match SURFACE_STATE.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    state.as_ref().and_then(|s| {
        s.static_grid.as_ref().map(|g| {
            (
                g.atlas_glyph_count,
                g.glyphs_per_frame,
                g.grid_rows,
                g.grid_cols,
                g.text_per_cell.clone(),
            )
        })
    })
}

// ---------------------------------------------------------------------------
// M3-S08: per-cell dynamic grid render path (runtime mirror)
// ---------------------------------------------------------------------------

/// Initializes the per-cell dynamic grid renderer from a `Vec<Vec<Cell>>`
/// snapshot. Idempotent: replaces the prior dynamic grid if any. Logs
/// `dynamic_grid_init_ok` for the test driver.
pub(crate) fn init_dynamic_grid(
    cells: &[Vec<crate::dynamic_grid::Cell>],
    font_size_px: f32,
    cell_w_px: f32,
    cell_h_px: f32,
) -> bool {
    let mut state = match SURFACE_STATE.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    let Some(s) = state.as_mut() else {
        log::error!(target: "WarpDynamicGrid", "init_dynamic_grid: no surface attached");
        return false;
    };

    // M3-S09: try the in-place fast path first. If the cached atlas covers
    // every glyph in the new snapshot AND geometry matches, this is a
    // ~1ms map+memcpy instead of the ~30-60ms shape+rasterize+pipeline
    // rebuild. Critical for sustained 60fps scroll.
    if let Some(grid) = s.dynamic_grid.as_mut() {
        let t0 = uptime_millis();
        match unsafe {
            grid.try_update_in_place(&s.device, cells, font_size_px, cell_w_px, cell_h_px)
        } {
            Ok(()) => {
                let dt = uptime_millis() - t0;
                log::info!(
                    target: "WarpDynamicGrid",
                    "dynamic_grid_fast_path_ok dt_ms={} instances={} fast_path_updates={} full_reinits={}",
                    dt, grid.instance_count, grid.fast_path_updates, grid.full_reinits
                );
                return true;
            }
            Err(reason) => {
                log::debug!(
                    target: "WarpDynamicGrid",
                    "dynamic_grid_fast_path_miss reason={} — falling back to full re-init",
                    reason
                );
            }
        }
    }

    // Slow path: full pipeline + atlas rebuild.
    unsafe {
        let _ = s.device.device_wait_idle();
        if let Some(old) = s.dynamic_grid.take() {
            old.destroy(&s.device);
        }
    }
    let t0 = uptime_millis();
    let grid = unsafe {
        crate::dynamic_grid::DynamicGrid::new(
            &s.instance,
            &s.device,
            s.phys_device,
            s.graphics_queue,
            s.command_pool,
            s.render_pass,
            cells,
            font_size_px,
            cell_w_px,
            cell_h_px,
        )
    };
    match grid {
        Ok(g) => {
            let dt = uptime_millis() - t0;
            log::info!(
                target: "WarpDynamicGrid",
                "dynamic_grid_init_ok dt_ms={} rows={} cols={} cell={}x{}px font_size_px={} \
                 atlas_glyphs={} glyph_quads={} bg_quads={}",
                dt, g.grid_rows, g.grid_cols, g.cell_w_px, g.cell_h_px, g.font_size_px,
                g.atlas_glyph_count, g.glyphs_per_frame, g.bg_quads_per_frame
            );
            s.dynamic_grid = Some(g);
            true
        }
        Err(e) => {
            log::error!(
                target: "WarpDynamicGrid",
                "dynamic_grid_init_fail reason={:?}", e
            );
            false
        }
    }
}

unsafe fn record_and_present_dynamic_grid(
    s: &mut VulkanSurface,
    clear_rgba: [f32; 4],
) -> Result<FrameOutcome, vk::Result> {
    let device = &s.device;

    let (image_index, acquire_suboptimal) = match s.swapchain_loader.acquire_next_image(
        s.swapchain,
        u64::MAX,
        s.image_available,
        vk::Fence::null(),
    ) {
        Ok(pair) => pair,
        Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => {
            return Ok(FrameOutcome::OutOfDate);
        }
        Err(e) => return Err(e),
    };

    let cmd_buf = s.command_buffers[image_index as usize];
    let begin_info = vk::CommandBufferBeginInfo::default()
        .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT);
    device.begin_command_buffer(cmd_buf, &begin_info)?;

    let clear_values = [vk::ClearValue {
        color: vk::ClearColorValue {
            float32: clear_rgba,
        },
    }];
    let rp_begin = vk::RenderPassBeginInfo::default()
        .render_pass(s.render_pass)
        .framebuffer(s.framebuffers[image_index as usize])
        .render_area(vk::Rect2D {
            offset: vk::Offset2D::default(),
            extent: s.extent,
        })
        .clear_values(&clear_values);
    device.cmd_begin_render_pass(cmd_buf, &rp_begin, vk::SubpassContents::INLINE);
    if let Some(grid) = s.dynamic_grid.as_ref() {
        grid.record_draw(
            device,
            cmd_buf,
            s.extent.width as f32,
            s.extent.height as f32,
        );
    }
    device.cmd_end_render_pass(cmd_buf);
    device.end_command_buffer(cmd_buf)?;

    let wait_semaphores = [s.image_available];
    let signal_semaphores = [s.render_finished_per_image[image_index as usize]];
    let wait_stages = [vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT];
    let cmd_bufs = [cmd_buf];
    let submit_info = vk::SubmitInfo::default()
        .wait_semaphores(&wait_semaphores)
        .wait_dst_stage_mask(&wait_stages)
        .command_buffers(&cmd_bufs)
        .signal_semaphores(&signal_semaphores);
    device.queue_submit(s.graphics_queue, &[submit_info], s.fence)?;

    let swapchains = [s.swapchain];
    let image_indices = [image_index];
    let present_info = vk::PresentInfoKHR::default()
        .wait_semaphores(&signal_semaphores)
        .swapchains(&swapchains)
        .image_indices(&image_indices);
    let present_result = s
        .swapchain_loader
        .queue_present(s.graphics_queue, &present_info);

    let present_suboptimal = match present_result {
        Ok(suboptimal) => suboptimal,
        Err(vk::Result::ERROR_OUT_OF_DATE_KHR) => {
            device.queue_wait_idle(s.graphics_queue)?;
            device.reset_fences(&[s.fence])?;
            device.reset_command_pool(s.command_pool, vk::CommandPoolResetFlags::empty())?;
            return Ok(FrameOutcome::OutOfDate);
        }
        Err(e) => {
            let _ = device.queue_wait_idle(s.graphics_queue);
            let _ = device.reset_fences(&[s.fence]);
            let _ = device.reset_command_pool(s.command_pool, vk::CommandPoolResetFlags::empty());
            return Err(e);
        }
    };

    device.queue_wait_idle(s.graphics_queue)?;
    device.reset_fences(&[s.fence])?;
    device.reset_command_pool(s.command_pool, vk::CommandPoolResetFlags::empty())?;

    if acquire_suboptimal || present_suboptimal {
        Ok(FrameOutcome::PresentedSuboptimal)
    } else {
        Ok(FrameOutcome::Presented)
    }
}

/// Submits a single frame consisting of a clear + the dynamic grid draw.
/// Returns `true` on successful present. If no grid is attached, this is
/// equivalent to `submit_clear_frame`.
pub(crate) fn submit_dynamic_grid_frame(clear_rgba: [f32; 4]) -> bool {
    let mut state = match SURFACE_STATE.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    let Some(s) = state.as_mut() else {
        return false;
    };
    if s.dynamic_grid.is_none() {
        log::debug!(target: "WarpDynamicGrid",
            "submit_dynamic_grid_frame called with no grid attached; falling back to clear");
    }
    match unsafe { record_and_present_dynamic_grid(s, clear_rgba) } {
        Ok(FrameOutcome::Presented) => {
            let n = if let Ok(mut c) = FRAME_COUNTER.lock() {
                *c += 1;
                *c
            } else {
                0
            };
            let ts = uptime_millis();
            log::info!(target: "WarpVulkan", "present_ok frame={} ts={}", n, ts);
            true
        }
        Ok(FrameOutcome::PresentedSuboptimal) => {
            let n = if let Ok(mut c) = FRAME_COUNTER.lock() {
                *c += 1;
                *c
            } else {
                0
            };
            let ts = uptime_millis();
            log::info!(target: "WarpVulkan", "present_ok frame={} ts={}", n, ts);
            log::warn!(target: "WarpVulkan", "swapchain suboptimal — recreating before next frame");
            if let Err(e) = unsafe { recreate_swapchain(s) } {
                log::error!(target: "WarpVulkan", "recreate after suboptimal failed: {:?}", e);
            }
            true
        }
        Ok(FrameOutcome::OutOfDate) => {
            log::warn!(target: "WarpVulkan", "swapchain out-of-date — recreating");
            if let Err(e) = unsafe { recreate_swapchain(s) } {
                log::error!(target: "WarpVulkan", "recreate failed: {:?}", e);
            }
            false
        }
        Err(e) => {
            log::error!(target: "WarpVulkan", "dynamic_grid present failed: {:?}", e);
            false
        }
    }
}

/// Whether a dynamic grid is currently attached. Used by JNI for
/// diagnostic logging.
#[allow(dead_code)]
pub(crate) fn dynamic_grid_attached() -> bool {
    let state = match SURFACE_STATE.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    state.as_ref().map(|s| s.dynamic_grid.is_some()).unwrap_or(false)
}

/// Diagnostic stats: (atlas_glyph_count, glyph_quads, bg_quads, rows, cols).
pub(crate) fn dynamic_grid_stats() -> Option<(u32, u32, u32, u32, u32)> {
    let state = match SURFACE_STATE.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    state.as_ref().and_then(|s| {
        s.dynamic_grid.as_ref().map(|g| {
            (
                g.atlas_glyph_count,
                g.glyphs_per_frame,
                g.bg_quads_per_frame,
                g.grid_rows,
                g.grid_cols,
            )
        })
    })
}

/// M3-S09 perf counters: (fast_path_updates, full_reinits) for the current
/// dynamic_grid (or None if no grid attached). Surfaced via the JNI to the
/// device-side test driver so the test asserts the fast path actually
/// fired during sustained scroll.
pub(crate) fn dynamic_grid_perf_counters() -> Option<(u64, u64)> {
    let state = match SURFACE_STATE.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    state
        .as_ref()
        .and_then(|s| s.dynamic_grid.as_ref().map(|g| (g.fast_path_updates, g.full_reinits)))
}
