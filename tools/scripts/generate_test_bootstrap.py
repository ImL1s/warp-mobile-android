import os
import json
import hashlib
import zipfile

def create_test_bootstrap():
    out_dir = os.path.join(os.getcwd(), "_bootstrap-out")
    os.makedirs(out_dir, exist_ok=True)

    zip_path = os.path.join(out_dir, "bootstrap-aarch64.zip")
    meta_path = os.path.join(out_dir, "bootstrap-metadata.json")

    # Sample binaries (bin/ and libexec/)
    binaries = [
        "bin/zsh",
        "bin/bash",
        "bin/sh",
        "bin/curl",
        "bin/git",
        "bin/ls",
        "bin/cat",
        "libexec/git-core/git",
        "libexec/git-core/git-remote-http",
        "libexec/git-core/git-remote-https",
        "libexec/git-core/git-daemon"
    ]

    # Create zip file containing ELF binaries
    elf_content = b"\x7fELF\x02\x01\x01\x00" + b"\x00" * 56
    with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as zf:
        for b in binaries:
            zf.writestr(b, elf_content)
        # add SYMLINKS.txt sidecar
        zf.writestr("SYMLINKS.txt", "bin/sh←bin/zsh\n")

    with open(zip_path, 'rb') as f:
        data = f.read()
        sha256 = hashlib.sha256(data).hexdigest()
        size_bytes = len(data)

    metadata = {
        "sha256": sha256,
        "size_bytes": size_bytes,
        "size_mb": round(size_bytes / (1024 * 1024), 2),
        "build_date": "2026-08-03T15:52:00Z",
        "package_count": 10,
        "package_list_file": "tools/scripts/m4-bootstrap-packages.txt",
        "warp_app_id": "dev.warp.mobile",
        "arch": "aarch64",
        "text_files_rewritten": 10,
        "elf_runpath_patched": 10,
        "files_with_upstream_app_id_remaining": 0
    }

    with open(meta_path, 'w') as f:
        json.dump(metadata, f, indent=2)

    print(f"Generated {zip_path} ({size_bytes} bytes, SHA256: {sha256}) and {meta_path}")

if __name__ == "__main__":
    create_test_bootstrap()
