package org.dynmap.diag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;

public class DynmapDiagPlugin extends JavaPlugin {
    private static final Logger log = Logger.getLogger("Minecraft");
    private static final String LOG_PREFIX = "[Dynmap-Diag] ";
    Plugin dynmap;
    DynmapAPI api;
    MarkerAPI markerapi;
    FileConfiguration cfg;
    MarkerSet set;
    long updperiod;
    AreaStyle defstyle;
    boolean stop;
    
    private class AreaStyle {
        String strokecolor;
        double strokeopacity;
        int strokeweight;
        String fillcolor;
        double fillopacity;

        AreaStyle(FileConfiguration cfg, String path) {
            strokecolor = cfg.getString(path+".strokeColor", "#FF0000");
            strokeopacity = cfg.getDouble(path+".strokeOpacity", 0.8);
            strokeweight = cfg.getInt(path+".strokeWeight", 3);
            fillcolor = cfg.getString(path+".fillColor", "#FF0000");
            fillopacity = cfg.getDouble(path+".fillOpacity", 0.35);
        }
    }
    
    public static void info(String msg) {
        log.log(Level.INFO, LOG_PREFIX + msg);
    }
    public static void severe(String msg) {
        log.log(Level.SEVERE, LOG_PREFIX + msg);
    }

    private class ChunkUpdate implements Runnable {
        public void run() {
            if(!stop)
                updateChunks();
        }
    }
    
    private Map<String, AreaMarker> resareas = new HashMap<String, AreaMarker>();
    
    private boolean isVisible(String id, String worldname) {
        return true;
    }
        
    private void addStyle(String resid, AreaMarker m) {
        AreaStyle as = defstyle;
        int sc = 0xFF0000;
        int fc = 0xFF0000;
        try {
            sc = Integer.parseInt(as.strokecolor.substring(1), 16);
            fc = Integer.parseInt(as.fillcolor.substring(1), 16);
        } catch (NumberFormatException nfx) {
        }
        m.setLineStyle(as.strokeweight, as.strokeopacity, sc);
        m.setFillStyle(as.fillopacity, fc);
    }
     
    enum direction { XPLUS, ZPLUS, XMINUS, ZMINUS };
        
    
    /**
     * Find caller that loaded chunk : first non-bukkit/non-MC class in stack
     */
    public String findCaller(StackTraceElement[] stk) {
        for(int i = 0; i < stk.length; i++) {   /* Skip first two - its us */
            String clsid = stk[i].getClassName();
            if(clsid.startsWith("org.bukkit.") || clsid.startsWith("net.minecraft.") || 
                    clsid.startsWith("java.") || clsid.startsWith("sun.") ||
                    clsid.startsWith("org.dynmap.diag."))
                continue;
            return clsid + " : " + stk[i].getMethodName();
        }
        return "minecraft";
    }
    public String buildTrace(StackTraceElement[] stk) {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < stk.length; i++) {
            String clsid = stk[i].getClassName();
            if(clsid.startsWith("java.") || clsid.startsWith("sun.") || clsid.startsWith("org.dynmap.diag."))
                continue;
            sb.append("\n" + clsid + " :" + stk[i].getMethodName() + " (" + stk[i].getFileName() + ":" + stk[i].getLineNumber() + ")");
        }
        return sb.toString();
    }
    /**
     * Find all contiguous blocks, set in target and clear in source
     */
    private int floodFillTarget(TileFlags src, TileFlags dest, int x, int y) {
        int cnt = 0;
        ArrayDeque<int[]> stack = new ArrayDeque<int[]>();
        stack.push(new int[] { x, y });
        
        while(stack.isEmpty() == false) {
            int[] nxt = stack.pop();
            x = nxt[0];
            y = nxt[1];
            if(src.getFlag(x, y)) { /* Set in src */
                src.setFlag(x, y, false);   /* Clear source */
                dest.setFlag(x, y, true);   /* Set in destination */
                cnt++;
                if(src.getFlag(x+1, y))
                    stack.push(new int[] { x+1, y });
                if(src.getFlag(x-1, y))
                    stack.push(new int[] { x-1, y });
                if(src.getFlag(x, y+1))
                    stack.push(new int[] { x, y+1 });
                if(src.getFlag(x, y-1))
                    stack.push(new int[] { x, y-1 });
            }
        }
        return cnt;
    }
    
    
    /* Handle chunks on specific world */
    private void handleChunksOnWorld(World w, Map<String, AreaMarker> newmap) {
        double[] x = null;
        double[] z = null;
        int poly_index = 0; /* Index of polygon for given faction */
        String label = "Loaded Chunks";
        
        Chunk[] chks = w.getLoadedChunks();
        /* Now, find who loaded these - group into sets */
        HashMap<String, LinkedList<Chunk>> chunks_by_loader = new HashMap<String, LinkedList<Chunk>>();
        for(Chunk c : chks) {
            String chunkid = idForChunk(c);
            if(c.isLoaded() == false) {
                log.info("Loaded chunk included an unloaded one! " + chunkid);
                continue;
            }
            ChunkRecord rec = chunkrecs.get(chunkid);
            String id;
            if(rec != null) {
                id = buildTrace(rec.load_stack);
                if(rec.unload_stack != null) {
                    log.info("Loaded chunk " + chunkid + " was loaded by: " + id + ", unloaded by: " + buildTrace(rec.unload_stack));
                }
            }
            else {
                id = "unknown";
            }
            LinkedList<Chunk> ll = chunks_by_loader.get(id);
            if(ll == null) {
                ll = new LinkedList<Chunk>();
                chunks_by_loader.put(id, ll);
            }
            ll.add(c);
        }
        /* Now, one outline set for each loader */
        for(String loaderid : chunks_by_loader.keySet()) {
            label = "Loaded by: " + loaderid;
            LinkedList<Chunk> ourchunks = chunks_by_loader.get(loaderid);
            LinkedList<Chunk> nodevals = new LinkedList<Chunk>();
            TileFlags curblks = new TileFlags();
            /* Loop through blocks: set flags on blockmaps */
            for(Chunk c : ourchunks) {
                curblks.setFlag(c.getX(), c.getZ(), true); /* Set flag for block */
                nodevals.addLast(c);
            }
            /* Loop through until we don't find more areas */
            while(nodevals != null) {
                LinkedList<Chunk> ournodes = null;
                LinkedList<Chunk> newlist = null;
                TileFlags ourblks = null;
                int minx = Integer.MAX_VALUE;
                int minz = Integer.MAX_VALUE;
                for(Chunk node : nodevals) {
                    int nodex = node.getX();
                    int nodez = node.getZ();
                    /* If we need to start shape, and this block is not part of one yet */
                    if((ourblks == null) && curblks.getFlag(nodex, nodez)) {
                        ourblks = new TileFlags();  /* Create map for shape */
                        ournodes = new LinkedList<Chunk>();
                        floodFillTarget(curblks, ourblks, nodex, nodez);   /* Copy shape */
                        ournodes.add(node); /* Add it to our node list */
                        minx = nodex; minz = nodez;
                    }
                    /* If shape found, and we're in it, add to our node list */
                    else if((ourblks != null) && ourblks.getFlag(nodex, nodez)) {
                        ournodes.add(node);
                        if(nodex < minx) {
                            minx = nodex; minz = nodez;
                        }
                        else if((nodex == minx) && (nodez < minz)) {
                            minz = nodez;
                        }
                    }
                    else {  /* Else, keep it in the list for the next polygon */
                        if(newlist == null) newlist = new LinkedList<Chunk>();
                        newlist.add(node);
                    }
                }
                nodevals = newlist; /* Replace list (null if no more to process) */
                if(ourblks != null) {
                    /* Trace outline of blocks - start from minx, minz going to x+ */
                    int init_x = minx;
                    int init_z = minz;
                    int cur_x = minx;
                    int cur_z = minz;
                    direction dir = direction.XPLUS;
                    ArrayList<int[]> linelist = new ArrayList<int[]>();
                    linelist.add(new int[] { init_x, init_z } ); // Add start point
                    while((cur_x != init_x) || (cur_z != init_z) || (dir != direction.ZMINUS)) {
                        switch(dir) {
                            case XPLUS: /* Segment in X+ direction */
                                if(!ourblks.getFlag(cur_x+1, cur_z)) { /* Right turn? */
                                    linelist.add(new int[] { cur_x+1, cur_z }); /* Finish line */
                                    dir = direction.ZPLUS;  /* Change direction */
                                }
                                else if(!ourblks.getFlag(cur_x+1, cur_z-1)) {  /* Straight? */
                                    cur_x++;
                                }
                                else {  /* Left turn */
                                    linelist.add(new int[] { cur_x+1, cur_z }); /* Finish line */
                                    dir = direction.ZMINUS;
                                    cur_x++; cur_z--;
                                }
                                break;
                            case ZPLUS: /* Segment in Z+ direction */
                                if(!ourblks.getFlag(cur_x, cur_z+1)) { /* Right turn? */
                                    linelist.add(new int[] { cur_x+1, cur_z+1 }); /* Finish line */
                                    dir = direction.XMINUS;  /* Change direction */
                                }
                                else if(!ourblks.getFlag(cur_x+1, cur_z+1)) {  /* Straight? */
                                    cur_z++;
                                }
                                else {  /* Left turn */
                                    linelist.add(new int[] { cur_x+1, cur_z+1 }); /* Finish line */
                                    dir = direction.XPLUS;
                                    cur_x++; cur_z++;
                                }
                                break;
                            case XMINUS: /* Segment in X- direction */
                                if(!ourblks.getFlag(cur_x-1, cur_z)) { /* Right turn? */
                                    linelist.add(new int[] { cur_x, cur_z+1 }); /* Finish line */
                                    dir = direction.ZMINUS;  /* Change direction */
                                }
                                else if(!ourblks.getFlag(cur_x-1, cur_z+1)) {  /* Straight? */
                                    cur_x--;
                                }
                                else {  /* Left turn */
                                    linelist.add(new int[] { cur_x, cur_z+1 }); /* Finish line */
                                    dir = direction.ZPLUS;
                                    cur_x--; cur_z++;
                                }
                                break;
                            case ZMINUS: /* Segment in Z- direction */
                                if(!ourblks.getFlag(cur_x, cur_z-1)) { /* Right turn? */
                                    linelist.add(new int[] { cur_x, cur_z }); /* Finish line */
                                    dir = direction.XPLUS;  /* Change direction */
                                }
                                else if(!ourblks.getFlag(cur_x-1, cur_z-1)) {  /* Straight? */
                                    cur_z--;
                                }
                                else {  /* Left turn */
                                    linelist.add(new int[] { cur_x, cur_z }); /* Finish line */
                                    dir = direction.XMINUS;
                                    cur_x--; cur_z--;
                                }
                                break;
                        }
                    }
                    /* Build information for specific area */
                    String polyid = "Chunks__" + w.getName() + "__" + poly_index;
                    int sz = linelist.size();
                    x = new double[sz];
                    z = new double[sz];
                    for(int i = 0; i < sz; i++) {
                        int[] line = linelist.get(i);
                        x[i] = (double)line[0] * 16.0;
                        z[i] = (double)line[1] * 16.0;
                    }
                    /* Find existing one */
                    AreaMarker m = resareas.remove(polyid); /* Existing area? */
                    if(m == null) {
                        m = set.createAreaMarker(polyid, label, false, w.getName(), x, z, false);
                        if(m == null) {
                            info("error adding area marker " + polyid);
                            return;
                        }
                    }
                    else {
                        m.setCornerLocations(x, z); /* Replace corner locations */
                        m.setLabel(label);   /* Update label */
                    }
                    /* Set line and fill properties */
                    addStyle(label, m);

                    /* Add to map */
                    newmap.put(polyid, m);
                    poly_index++;
                }
            }
        }
    }
    
    /* Update Chunk information */
    private void updateChunks() {
        Map<String,AreaMarker> newmap = new HashMap<String,AreaMarker>(); /* Build new map */
        
        /* Loop through worlds */
        for(World w : getServer().getWorlds()) {
            handleChunksOnWorld(w, newmap);
        }
        
        /* Now, review old map - anything left is gone */
        for(AreaMarker oldm : resareas.values()) {
            oldm.deleteMarker();
        }
        /* And replace with new map */
        resareas = newmap;
        
        getServer().getScheduler().scheduleSyncDelayedTask(this, new ChunkUpdate(), updperiod);
        
    }
    
    private static class ChunkRecord {
        long    load_time;
        StackTraceElement[] load_stack;
        long    unload_time;
        StackTraceElement[] unload_stack;
    }
    
    private HashMap<String, ChunkRecord> chunkrecs = new HashMap<String, ChunkRecord>();
    
    private String idForChunk(Chunk c) {
        return c.getWorld().getName() + ":" + c.getX() + "," + c.getZ();
    }
    
    private class OurServerListener implements Listener {
        @SuppressWarnings("unused")
        @EventHandler
        public void onPluginEnable(PluginEnableEvent event) {
            Plugin p = event.getPlugin();
            String name = p.getDescription().getName();
            if(name.equals("dynmap")) {
                if(dynmap.isEnabled())
                    activate();
            }
        }
        @EventHandler(priority=EventPriority.MONITOR)
        public void onChunkLoad(ChunkLoadEvent event) {
            Chunk c = event.getChunk();
            /* Get stack */
            ChunkRecord rec = new ChunkRecord();
            rec.load_stack = Thread.currentThread().getStackTrace();
            rec.load_time = System.currentTimeMillis();
            rec.unload_stack = null;
            rec.unload_time = 0;
            String chunkid = idForChunk(c);
            chunkrecs.put(chunkid,  rec);
        }
        @EventHandler(priority=EventPriority.MONITOR)
        public void onChunkUnload(ChunkUnloadEvent event) {
            Chunk c = event.getChunk();
            String chunkid = idForChunk(c);
            ChunkRecord rec = chunkrecs.get(chunkid);
            if(rec == null) {
                return;
            }
            rec.unload_time = System.currentTimeMillis();
            rec.unload_stack = Thread.currentThread().getStackTrace();
        }
    }
    
    public void onEnable() {
        info("initializing");
        PluginManager pm = getServer().getPluginManager();
        /* Get dynmap */
        dynmap = pm.getPlugin("dynmap");
        if(dynmap == null) {
            severe("Cannot find dynmap!");
            return;
        }
        api = (DynmapAPI)dynmap; /* Get API */

        getServer().getPluginManager().registerEvents(new OurServerListener(), this);        

        /* If enabled, activate */
        if(dynmap.isEnabled())
            activate();
    }
    
    private void activate() {
        markerapi = api.getMarkerAPI();
        if(markerapi == null) {
            severe("Error loading dynmap marker API!");
            return;
        }
        /* Load configuration */
        FileConfiguration cfg = getConfig();
        cfg.options().copyDefaults(true);   /* Load defaults, if needed */
        this.saveConfig();  /* Save updates, if needed */
        
        /* Now, add marker set for chunks (make it transient) */
        set = markerapi.getMarkerSet("diags_chunks.markerset");
        if(set == null)
            set = markerapi.createMarkerSet("diags_chunks.markerset", cfg.getString("chunklayer.name", "Loaded Chunks"), null, false);
        else
            set.setMarkerSetLabel(cfg.getString("chunklayer.name", "Loaded Chunks"));
        if(set == null) {
            severe("Error creating marker set");
            return;
        }
        int minzoom = cfg.getInt("chunklayer.minzoom", 0);
        if(minzoom > 0)
            set.setMinZoom(minzoom);
        set.setLayerPriority(cfg.getInt("chunklayer.layerprio", 10));
        set.setHideByDefault(cfg.getBoolean("chunklayer.hidebydefault", false));

        /* Get style information */
        defstyle = new AreaStyle(cfg, "regionstyle");

        /* Set up update job - based on perion */
        int per = cfg.getInt("update.chunkperiod", 30);
        if(per < 15) per = 15;
        updperiod = (per*20);
        stop = false;
        
        getServer().getScheduler().scheduleSyncDelayedTask(this, new ChunkUpdate(), 40);   /* First time is 2 seconds */
        
        info("version " + this.getDescription().getVersion() + " is activated");
    }

    public void onDisable() {
        if(set != null) {
            set.deleteMarkerSet();
            set = null;
        }
        resareas.clear();
        stop = true;
    }

}
