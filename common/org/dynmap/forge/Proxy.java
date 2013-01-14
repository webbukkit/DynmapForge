package org.dynmap.forge;

/**
 * Server side proxy - methods for creating and cleaning up plugin
 */
public class Proxy
{
    public Proxy()
    {
    }
	public DynmapPlugin startServer() {
		DynmapPlugin plugin = new DynmapPlugin();
		plugin.onEnable();
		return plugin;
	}
	public void stopServer(DynmapPlugin plugin) {
		plugin.onDisable();
	}
}
