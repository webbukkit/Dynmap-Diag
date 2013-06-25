package org.dynmap.diag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.Marker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerIcon;
import org.dynmap.markers.MarkerSet;

public class DynmapDiagPlugin extends JavaPlugin {
    private static final Logger log = Logger.getLogger("Minecraft");
    private static final String LOG_PREFIX = "[Dynmap-Diag] ";
    Plugin dynmap;
    DynmapAPI api;
    MarkerAPI markerapi;
    FileConfiguration cfg;
    MarkerSet set;
    MarkerSet entityset;
    long updperiod;
    AreaStyle defstyle;
    MarkerIcon entitymarker;
    boolean stop;
    boolean include_stack_chunk;
    boolean include_stack_entities;
    boolean paused;
    private static final String NOPLUGINMSG = "Associated plugins(s):<br>None (Loaded by CraftBukkit)<br>";
    
    private class AreaStyle {
        String strokecolor;
        String nopluginstrokecolor;
        double strokeopacity;
        int strokeweight;
        String fillcolor;
        String nopluginfillcolor;
        double fillopacity;

        AreaStyle(FileConfiguration cfg, String path) {
            strokecolor = cfg.getString(path+".strokeColor", "#FF0000");
            nopluginstrokecolor = cfg.getString(path+".nonPluginStrokeColor", "#0000FF");
            strokeopacity = cfg.getDouble(path+".strokeOpacity", 0.8);
            strokeweight = cfg.getInt(path+".strokeWeight", 3);
            fillcolor = cfg.getString(path+".fillColor", "#FF0000");
            nopluginfillcolor = cfg.getString(path+".nonPluginFillColor", "#0000FF");
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
            if(!stop) {
                if(!paused) {
                    updateChunks();
                }
                getServer().getScheduler().scheduleSyncDelayedTask(DynmapDiagPlugin.this, new ChunkUpdate(), updperiod);
            }
        }
    }
    
    private Map<String, AreaMarker> resareas = new HashMap<String, AreaMarker>();
    private Map<String, Marker> resmarkers = new HashMap<String, Marker>();
            
    private void addStyle(AreaMarker m, boolean plugin) {
        AreaStyle as = defstyle;
        int sc = 0xFF0000;
        int fc = 0xFF0000;
        try {
            if(plugin) {
                sc = Integer.parseInt(as.strokecolor.substring(1), 16);
                fc = Integer.parseInt(as.fillcolor.substring(1), 16);
            }
            else {
                sc = Integer.parseInt(as.nopluginstrokecolor.substring(1), 16);
                fc = Integer.parseInt(as.nopluginfillcolor.substring(1), 16);
            }
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
    public String buildTrace(Record rec, IdentityHashMap<ClassLoader, String> cload, boolean incstack) {
        StringBuilder sb = new StringBuilder();
        String plugin = null;
        String lastplugin = null;
        StackTraceElement[] stk = rec.load_stack;
        rec.pluginfound = false;
        for(int i = 0; i < stk.length; i++) {
            String clsid = stk[i].getClassName();
            if(clsid.startsWith("java.") || clsid.startsWith("sun.") || clsid.startsWith("org.dynmap.diag."))
                continue;
            try {
                Class<?> cls = Class.forName(clsid);
                String pluginid = cload.get(cls.getClassLoader());
                if(pluginid != null) {
                    if(plugin == null) {
                        plugin = pluginid;
                    }
                    else if(!plugin.equals(lastplugin)) {
                        plugin = pluginid + ",<br>called by " + plugin;
                    }
                    lastplugin = pluginid;
                    rec.pluginfound = true;
                }
            } catch (ClassNotFoundException cnfx) {
            }
            if(incstack) {
                sb.append("<br>" + clsid + " :" + stk[i].getMethodName() + " (" + stk[i].getFileName() + ":" + stk[i].getLineNumber());
            }
        }
        StringBuilder sb2 = new StringBuilder();
        if(plugin != null) {
            sb2.append("Associated plugin(s):<br>").append(plugin);
        }
        else {
            sb2.append(NOPLUGINMSG);
        }
        if(incstack) {
            sb2.append("<br>Stack:").append(sb);
        }
        return sb2.toString();
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
    
    /* Handle entities on specific world */
    private void handleEntitiesOnWorld(World w, Map<String, Marker> newmap,IdentityHashMap<ClassLoader, String> cload) {
        if(entityset == null) return;
        
        int idx = 0;
        
        for(Entity ent : w.getEntities()) {
            Location loc = ent.getLocation();
            /* See if entity that is in unloaded chunk */
            if(w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4) == false) {
                String id = "orphan_" + idx;
                Marker m = resmarkers.remove(id);
                String trc;
                EntityRecord rec = entrecs.get(ent.getEntityId());
                if(rec != null)
                    trc = buildTrace(rec, cload, this.include_stack_entities);
                else
                    trc = NOPLUGINMSG;
                
                if(m == null) { /* Not found?  Need new one */
                    m = entityset.createMarker(id, ent.getClass().getName(), w.getName(), 
                            loc.getX(), loc.getY(), loc.getZ(), 
                            entitymarker, false);
                }
                else {  /* Else, update position if needed */
                    m.setLocation(w.getName(), loc.getX(), loc.getY(), loc.getZ());
                    m.setLabel(ent.getClass().getName());
                }
                if (m != null) {
                    m.setDescription("Class:" + ent.getClass().getName() + "<br/>" + trc);
                
                    newmap.put(id, m);
                }
                idx++;
            }
        }
    }
    
    /* Handle chunks on specific world */
    private void handleChunksOnWorld(World w, Map<String, AreaMarker> newmap, IdentityHashMap<ClassLoader, String> cload) {
        if(set == null) return;
        
        double[] x = null;
        double[] z = null;
        int poly_index = 0; /* Index of polygon for given faction */
        String label = "Loaded Chunks";
        
        Chunk[] chks = w.getLoadedChunks();
        /* Now, find who loaded these - group into sets */
        HashMap<String, LinkedList<Chunk>> chunks_by_loader = new HashMap<String, LinkedList<Chunk>>();
        HashSet<String> plugins = new HashSet<String>();
        for(Chunk c : chks) {
            String chunkid = idForChunk(c);
            if(c.isLoaded() == false) {
                log.info("Loaded chunk included an unloaded one! " + chunkid);
                continue;
            }
            ChunkRecord rec = chunkrecs.get(chunkid);
            String id;
            if(rec != null) {
                id = buildTrace(rec, cload, this.include_stack_chunk);
            }
            else {
                id = NOPLUGINMSG;
            }
            LinkedList<Chunk> ll = chunks_by_loader.get(id);
            if(ll == null) {
                ll = new LinkedList<Chunk>();
                chunks_by_loader.put(id, ll);
            }
            ll.add(c);
            if((rec != null) && rec.pluginfound) {
                plugins.add(id);
            }
        }
        /* Now, one outline set for each loader */
        for(String loaderid : chunks_by_loader.keySet()) {
            label = loaderid;
            boolean plugin = plugins.contains(label);
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
                        m = set.createAreaMarker(polyid, "Chunks", false, w.getName(), x, z, false);
                        if(m == null) {
                            info("error adding area marker " + polyid);
                            return;
                        }
                    }
                    else {
                        m.setCornerLocations(x, z); /* Replace corner locations */
                    }
                    m.setDescription(label);
                    /* Set line and fill properties */
                    addStyle(m, plugin);

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
        Map<String, Marker> newmap2 = new HashMap<String,Marker>();
        
        /* Find class loaders for loaded plugins */
        IdentityHashMap<ClassLoader, String> cloaders = new IdentityHashMap<ClassLoader, String>();
        for(Plugin p : getServer().getPluginManager().getPlugins()) {
            if(p == this) continue;
            cloaders.put(p.getClass().getClassLoader(), p.getName());
        }
        /* Loop through worlds */
        for(World w : getServer().getWorlds()) {
            handleChunksOnWorld(w, newmap, cloaders);
            handleEntitiesOnWorld(w, newmap2, cloaders);
        }
        
        /* Now, review old map - anything left is gone */
        for(AreaMarker oldm : resareas.values()) {
            oldm.deleteMarker();
        }
        for(Marker oldm : resmarkers.values()) {
            oldm.deleteMarker();
        }
        /* And replace with new map */
        resareas = newmap;
        resmarkers = newmap2;
        
    }
    private static class Record {
        StackTraceElement[] load_stack;
        boolean pluginfound;
    }
    
    private static class ChunkRecord extends Record {
    }
    private HashMap<String, ChunkRecord> chunkrecs = new HashMap<String, ChunkRecord>();

    private static class EntityRecord extends Record {
    }
    private HashMap<Integer, EntityRecord> entrecs = new HashMap<Integer, EntityRecord>();
    
    private String idForChunk(Chunk c) {
        return c.getWorld().getName() + ":" + c.getX() + "," + c.getZ();
    }
    
    private class OurServerListener implements Listener {
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
        }
        @EventHandler(priority=EventPriority.MONITOR)
        public void onCreatureSpawn(CreatureSpawnEvent event) {
            if(event.isCancelled()) return;
            EntityRecord rec = new EntityRecord();
            rec.load_stack = Thread.currentThread().getStackTrace();
            entrecs.put(event.getEntity().getEntityId(),  rec);
        }
        @EventHandler(priority=EventPriority.MONITOR)
        public void onEntityDeath(EntityDeathEvent event) {
            entrecs.remove(event.getEntity().getEntityId());
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
        cfg = getConfig();
        cfg.options().copyDefaults(true);   /* Load defaults, if needed */
        this.saveConfig();  /* Save updates, if needed */

        initialize();

        /* Set up update job - based on perion */
        int per = cfg.getInt("update.chunkperiod", 30);
        if(per < 15) per = 15;
        updperiod = (per*20);
        stop = false;
        
        getServer().getScheduler().scheduleSyncDelayedTask(this, new ChunkUpdate(), 40);   /* First time is 2 seconds */
        
        info("version " + this.getDescription().getVersion() + " is activated");
    }
    
    private void initialize() {
        /* Now, add marker set for chunks (make it transient) */
        if(cfg.getBoolean("chunklayer.enabled", true)) {
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
            defstyle = new AreaStyle(cfg, "chunkstyle");
            
            this.include_stack_chunk = cfg.getBoolean("chunklayer.include-stack", false);
        }
        else if(set != null) {
            for(AreaMarker am : resareas.values()) {
                am.deleteMarker();
            }
            resareas.clear();
            set.deleteMarkerSet();
            set = null;
            resareas.clear();
        }
        /* Now, add marker set for entities (make it transient) */
        if(cfg.getBoolean("entitylayer.enabled", true)) {
            entityset = markerapi.getMarkerSet("diags_entities.markerset");
            if(entityset == null)
                entityset = markerapi.createMarkerSet("diags_entities.markerset", cfg.getString("entitylayer.name", "Leaked Entities"), null, false);
            else
                entityset.setMarkerSetLabel(cfg.getString("entitylayer.name", "Leaked Entities"));
            if(entityset == null) {
                severe("Error creating marker set");
                return;
            }
            entitymarker = markerapi.getMarkerIcon(cfg.getString("entitylayer.marker", MarkerIcon.DEFAULT));
            int minzoom = cfg.getInt("entitylayer.minzoom", 0);
            if(minzoom > 0)
                entityset.setMinZoom(minzoom);
            entityset.setLayerPriority(cfg.getInt("entitylayer.layerprio", 10));
            entityset.setHideByDefault(cfg.getBoolean("entitylayer.hidebydefault", false));
            this.include_stack_entities = cfg.getBoolean("entitylayer.include-stack", false);
        }
        else if(entityset != null) {
            for(Marker m : resmarkers.values()) {
                m.deleteMarker();
            }
            resmarkers.clear();
            entityset.deleteMarkerSet();
            entityset = null;
        }
    }

    public void onDisable() {
        if(set != null) {
            set.deleteMarkerSet();
            set = null;
        }
        if(entityset != null) {
            entityset.deleteMarkerSet();
            entityset = null;
        }
        resareas.clear();
        resmarkers.clear();

        stop = true;
    }
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String[] args) {
        if(cmd.getName().equals("dyndiag")) {
            if(sender.isOp() == false) {
                sender.sendMessage("Only operators can use this command");
                return false;
            }
            if(args.length < 2) {
                if(args.length == 1) {
                    if(args[0].equals("pause")) {
                        paused = true;
                        sender.sendMessage("Layer updates are paused");
                    }
                    else if(args[0].equals("unpause")) {
                        paused = false;
                        sender.sendMessage("Layer updates are unpaused");
                        updateChunks();
                    }
                    else if(args[0].equals("update")) {
                        updateChunks();
                        sender.sendMessage("Layers updated");
                    }
                    else {
                        return false;
                    }
                    return true;
                }
                return false;
            }
            if(args[0].equals("show")) {
                if(args[1].equals("chunks")) {
                    cfg.set("chunklayer.enabled", true);
                    sender.sendMessage("Chunk layer enabled");
                }
                else if(args[1].equals("entities")) {
                    cfg.set("entitylayer.enabled", true);
                    sender.sendMessage("Entity layer enabled");
                }
                else {
                    sender.sendMessage("Unknown layer: " + args[1]);
                    return false;
                }
            }
            else if(args[0].equals("hide")) {
                if(args[1].equals("chunks")) {
                    cfg.set("chunklayer.enabled", false);
                    sender.sendMessage("Chunk layer disabled");
                }
                else if(args[1].equals("entities")) {
                    cfg.set("entitylayer.enabled", false);
                    sender.sendMessage("Entity layer disabled");
                }
                else {
                    sender.sendMessage("Unknown layer: " + args[1]);
                    return false;
                }
            }
            else if(args[0].equals("showstack")) {
                if(args[1].equals("chunks")) {
                    cfg.set("chunklayer.include-stack", true);
                    sender.sendMessage("Chunk layer will include call stack");
                }
                else if(args[1].equals("entities")) {
                    cfg.set("entitylayer.include-stack", true);
                    sender.sendMessage("Entity layer will include call stack");
                }
                else {
                    sender.sendMessage("Unknown layer: " + args[1]);
                    return false;
                }
            }
            else if(args[0].equals("hidestack")) {
                if(args[1].equals("chunks")) {
                    cfg.set("chunklayer.include-stack", false);
                    sender.sendMessage("Chunk layer will notinclude call stack");
                }
                else if(args[1].equals("entities")) {
                    cfg.set("entitylayer.include-stack", false);
                    sender.sendMessage("Entity layer will notinclude call stack");
                }
                else {
                    sender.sendMessage("Unknown layer: " + args[1]);
                    return false;
                }
            }
            this.saveConfig();  /* Save updates, if needed */
            
            this.initialize();
            
            this.updateChunks();

            return true;
        }
        return false;
    }
}
