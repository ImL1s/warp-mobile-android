package dev.warp.mobile.panes

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LaunchPane(
    val cwd: String = "",
    val envVars: Map<String, String> = emptyMap(),
    val startupCommand: String = ""
)

data class LaunchConfiguration(
    val panes: List<LaunchPane> = emptyList()
)

object LaunchConfigManager {
    fun save(config: LaunchConfiguration, file: File) {
        try {
            val root = JSONObject()
            val panesArray = JSONArray()
            
            for (pane in config.panes) {
                val paneObj = JSONObject()
                paneObj.put("cwd", pane.cwd)
                paneObj.put("startupCommand", pane.startupCommand)
                
                val envObj = JSONObject()
                pane.envVars.forEach { (k, v) ->
                    envObj.put(k, v)
                }
                paneObj.put("envVars", envObj)
                
                panesArray.put(paneObj)
            }
            
            root.put("panes", panesArray)
            file.writeText(root.toString())
        } catch (e: Exception) {
            // Handle save error
        }
    }
    
    fun load(file: File): LaunchConfiguration? {
        if (!file.exists()) return null
        
        return try {
            val content = file.readText()
            val root = JSONObject(content)
            
            val panesArray = root.optJSONArray("panes") ?: return LaunchConfiguration()
            val panes = mutableListOf<LaunchPane>()
            
            for (i in 0 until panesArray.length()) {
                val paneObj = panesArray.optJSONObject(i) ?: continue
                
                val cwd = paneObj.optString("cwd", "")
                val startupCommand = paneObj.optString("startupCommand", "")
                
                val envObj = paneObj.optJSONObject("envVars")
                val envVars = mutableMapOf<String, String>()
                if (envObj != null) {
                    val keys = envObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        envVars[key] = envObj.optString(key)
                    }
                }
                
                panes.add(LaunchPane(cwd, envVars, startupCommand))
            }
            
            LaunchConfiguration(panes)
        } catch (e: Exception) {
            null
        }
    }
}
