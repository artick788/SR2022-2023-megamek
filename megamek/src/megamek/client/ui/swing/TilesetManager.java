/*
* MegaMek -
* Copyright (C) 2002, 2003, 2004 Ben Mazur (bmazur@sev.org)
* Copyright (C) 2018 The MegaMek Team
*
* This program is free software; you can redistribute it and/or modify it under
* the terms of the GNU General Public License as published by the Free Software
* Foundation; either version 2 of the License, or (at your option) any later
* version.
*
* This program is distributed in the hope that it will be useful, but WITHOUT
* ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
* FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
* details.
*/

/*
 * TilesetManager.java
 *
 * Created on April 15, 2002, 11:41 PM
 */

package megamek.client.ui.swing;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.MediaTracker;
import java.awt.Polygon;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.awt.image.MemoryImageSource;
import java.awt.image.PixelGrabber;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import megamek.client.ui.ITilesetManager;
import megamek.client.ui.swing.MechTileset.MechEntry;
import megamek.client.ui.swing.boardview.BoardView1;
import megamek.client.ui.swing.util.ImageCache;
import megamek.client.ui.swing.util.ImageFileFactory;
import megamek.client.ui.swing.util.PlayerColors;
import megamek.common.Configuration;
import megamek.common.Entity;
import megamek.common.IBoard;
import megamek.common.IGame;
import megamek.common.IHex;
import megamek.common.IPlayer;
import megamek.common.Infantry;
import megamek.common.Mech;
import megamek.common.Minefield;
import megamek.common.Protomech;
import megamek.common.QuadVee;
import megamek.common.preference.IClientPreferences;
import megamek.common.preference.IPreferenceChangeListener;
import megamek.common.preference.PreferenceChangeEvent;
import megamek.common.preference.PreferenceManager;
import megamek.common.util.DirectoryItems;
import megamek.common.util.ImageUtil;
import megamek.common.util.MegaMekFile;

/**
 * Handles loading and manipulating images from both the mech tileset and the
 * terrain tileset.
 *
 * @author Ben
 */
public class TilesetManager implements IPreferenceChangeListener, ITilesetManager {
    
    public static final String DIR_NAME_WRECKS = "wrecks"; //$NON-NLS-1$

    public static final String FILENAME_DEFAULT_HEX_SET = "defaulthexset.txt"; //$NON-NLS-1$

    private static final String FILENAME_NIGHT_IMAGE = new File("transparent", "night.png").toString();  //$NON-NLS-1$  //$NON-NLS-2$
    private static final String FILENAME_HEX_MASK = new File("transparent", "HexMask.png").toString();  //$NON-NLS-1$  //$NON-NLS-2$
    private static final String FILENAME_ARTILLERY_AUTOHIT_IMAGE = "artyauto.gif"; //$NON-NLS-1$
    private static final String FILENAME_ARTILLERY_ADJUSTED_IMAGE = "artyadj.gif"; //$NON-NLS-1$
    private static final String FILENAME_ARTILLERY_INCOMING_IMAGE = "artyinc.gif"; //$NON-NLS-1$
    private static final File FILENAME_DAMAGEDECAL_LIGHT = new File("units/DamageDecals", "DmgLight.png"); //$NON-NLS-1$
    private static final File FILENAME_DAMAGEDECAL_MODERATE = new File("units/DamageDecals", "DmgModerate.png"); //$NON-NLS-1$
    private static final File FILENAME_DAMAGEDECAL_HEAVY = new File("units/DamageDecals", "DmgHeavy.png"); //$NON-NLS-1$
    private static final File FILENAME_DAMAGEDECAL_CRIPPLED = new File("units/DamageDecals", "DmgCrippled.png"); //$NON-NLS-1$
    private static final File FILE_SMOKE_SML = new File("units/DamageDecals", "Smoke1.png"); //$NON-NLS-1$
    private static final File FILE_SMOKE_MED = new File("units/DamageDecals", "Smoke2.png"); //$NON-NLS-1$
    private static final File FILE_SMOKE_LRG = new File("units/DamageDecals", "Smoke3.png"); //$NON-NLS-1$
    private static final File FILE_SMOKEFIRE_SML = new File("units/DamageDecals", "SmokeFire1.png"); //$NON-NLS-1$
    private static final File FILE_SMOKEFIRE_MED = new File("units/DamageDecals", "SmokeFire2.png"); //$NON-NLS-1$
    private static final File FILE_SMOKEFIRE_LRG = new File("units/DamageDecals", "SmokeFire3.png"); //$NON-NLS-1$
    private static final File FILE_DAMAGEDECAL_EMPTY = new File("units/DamageDecals", "Transparent.png"); //$NON-NLS-1$

    public static final int ARTILLERY_AUTOHIT = 0;
    public static final int ARTILLERY_ADJUSTED = 1;
    public static final int ARTILLERY_INCOMING = 2;

    // component to load images to
    private BoardView1 boardview;

    // keep tracking of loading images
    private MediaTracker tracker;
    private boolean started = false;
    private boolean loaded = false;

    // keep track of camo images
    private DirectoryItems camos;

    // mech images
    private MechTileset mechTileset = new MechTileset(Configuration.unitImagesDir());
    private MechTileset wreckTileset = new MechTileset(
            new MegaMekFile(Configuration.unitImagesDir(), DIR_NAME_WRECKS).getFile());
    private ArrayList<EntityImage> mechImageList = new ArrayList<EntityImage>();
    private HashMap<ArrayList<Integer>, EntityImage> mechImages = new HashMap<ArrayList<Integer>, EntityImage>();

    // hex images
    private HexTileset hexTileset;

    private Image minefieldSign;
    private Image nightFog;

    /** An opaque hex shape used to limit draw operations to the exact hex shape. */
    private Image hexMask;

    private Image artilleryAutohit;
    private Image artilleryAdjusted;
    private Image artilleryIncoming;
    
    // Damage decal images
    private Image dmgLight;
    private Image dmgModerate;
    private Image dmgHeavy;
    private Image dmgCrippled;
    private Image SmokeSml;
    private Image SmokeMed;
    private Image SmokeLrg;
    private Image SmokeFireSml;
    private Image SmokeFireMed;
    private Image SmokeFireLrg;
    private Image dmgEmpty;
    private boolean decalLoaded = false;
    /**
     * Hexes under the effects of ECM have a shaded "static" image displayed,
     * to represent the noise generated by ECM.  This is a cache that stores
     * images for various colors (for Players, and possibly multiple players
     * in the same hex).
     */
    private HashMap<Color, Image> ecmStaticImages = new HashMap<Color, Image>();
    
    /**
     * Creates new TilesetManager
     */
    public TilesetManager(BoardView1 bv) throws IOException {
        boardview = bv;
        hexTileset = new HexTileset(boardview.game);
        tracker = new MediaTracker(boardview);
        try {
            camos = new DirectoryItems(
                    Configuration.camoDir(),
                    "", //$NON-NLS-1$
                    ImageFileFactory.getInstance()
            );
        } catch (Exception e) {
            camos = null;
        }
        mechTileset.loadFromFile("mechset.txt"); //$NON-NLS-1$
        wreckTileset.loadFromFile("wreckset.txt"); //$NON-NLS-1$
        try {
            hexTileset.incDepth = 0;
            hexTileset.loadFromFile(PreferenceManager.getClientPreferences().getMapTileset());
        } catch (Exception FileNotFoundException) {
            System.out.println("Error loading tileset, "
                    + "reverting to default hexset! " + "Could not find file: "
                    + PreferenceManager.getClientPreferences().getMapTileset());
            if (!new MegaMekFile(Configuration.hexesDir(), FILENAME_DEFAULT_HEX_SET).getFile().exists()){
                createDefaultHexSet();
            }
            hexTileset.loadFromFile(FILENAME_DEFAULT_HEX_SET);
        }
        PreferenceManager.getClientPreferences().addPreferenceChangeListener(
                this);
    }

    public void preferenceChange(PreferenceChangeEvent e) {
        if (e.getName().equals(IClientPreferences.MAP_TILESET)) {
            HexTileset hts = new HexTileset(boardview.game);
            try {
                hexTileset.incDepth = 0;
                hts.loadFromFile((String) e.getNewValue());
                hexTileset = hts;
                boardview.clearHexImageCache();
            } catch (IOException ex) {
                return;
            }
        }
    }

    public Image iconFor(Entity entity) {
        ArrayList<Integer> temp = new ArrayList<Integer>();
        temp.add(entity.getId());
        temp.add(-1);
        EntityImage entityImage = mechImages.get(temp);
        if (entityImage == null) {
            // probably double_blind. Try to load on the fly
            System.out
                    .println("Loading icon for " + entity.getShortNameRaw() + " on the fly."); //$NON-NLS-1$ //$NON-NLS-2$
            loadImage(entity, -1);
            entityImage = mechImages.get(temp);
            if (entityImage == null) {
                // now it's a real problem
                System.out
                        .println("Unable to load icon for entity: " + entity.getShortNameRaw()); //$NON-NLS-1$
                // Try to get a default image, so something is displayed
                MechEntry defaultEntry = mechTileset.genericFor(entity, -1);
                if (defaultEntry.getImage() == null) {
                    defaultEntry.loadImage(boardview);
                }
                if (defaultEntry.getImage() != null) {
                    return ImageUtil.getScaledImage(defaultEntry.getImage(), 56,
                            48);
                } else {
                    return null;
                }
            }
        }
        return entityImage.getIcon();
    }

    public Image wreckMarkerFor(Entity entity, int secondaryPos) {
        ArrayList<Integer> temp = new ArrayList<Integer>();
        temp.add(entity.getId());
        temp.add(secondaryPos);
        EntityImage entityImage = mechImages.get(temp);
        if (entityImage == null) {
            // probably double_blind. Try to load on the fly
            System.out
                    .println("Loading wreckMarker image for " + entity.getShortNameRaw() + " on the fly."); //$NON-NLS-1$ //$NON-NLS-2$
            loadImage(entity, secondaryPos);
            entityImage = mechImages.get(temp);
            if (entityImage == null) {
                // now it's a real problem
                System.out
                        .println("Unable to load wreckMarker image for entity: " + entity.getShortNameRaw()); //$NON-NLS-1$
                // Try to get a default image, so something is displayed
                MechEntry defaultEntry = wreckTileset.genericFor(entity, -1);
                if (defaultEntry.getImage() == null) {
                    defaultEntry.loadImage(boardview);
                }
                return defaultEntry.getImage();
            }
        }
        return entityImage.getWreckFacing(entity.getFacing());
    }

    /**
     * Return the image for the entity
     */
    public Image imageFor(Entity entity) {
        return imageFor(entity, -1);
    }

    public Image imageFor(Entity entity, int secondaryPos) {
        // mechs look like they're facing their secondary facing
        // (except QuadVees, which are using turrets instead of torso twists
        if (((entity instanceof Mech) || (entity instanceof Protomech))
                && !(entity instanceof QuadVee)) {
            return imageFor(entity, entity.getSecondaryFacing(), secondaryPos);
        }
        return imageFor(entity, entity.getFacing(), secondaryPos);
    }

    public Image imageFor(Entity entity, int facing, int secondaryPos) {
        ArrayList<Integer> temp = new ArrayList<Integer>();
        temp.add(entity.getId());
        temp.add(secondaryPos);
        EntityImage entityImage = mechImages.get(temp);
        if (entityImage == null) {
            // probably double_blind. Try to load on the fly
            System.out
                    .println("Loading image for " + entity.getShortNameRaw() + " on the fly."); //$NON-NLS-1$ //$NON-NLS-2$
            loadImage(entity, secondaryPos);
            entityImage = mechImages.get(temp);
            if (entityImage == null) {
                // now it's a real problem
                System.out
                        .println("Unable to load image for entity: " + entity.getShortNameRaw()); //$NON-NLS-1$
                // Try to get a default image, so something is displayed
                MechEntry defaultEntry = mechTileset.genericFor(entity, -1);
                if (defaultEntry.getImage() == null) {
                    defaultEntry.loadImage(boardview);
                }
                return defaultEntry.getImage();
            }
        }
        // get image rotated for facing
        return entityImage.getFacing(facing);
    }

    /**
     * Return the base image for the hex
     */
    public Image baseFor(IHex hex) {
        return hexTileset.getBase(hex, boardview);
    }

    /**
     * Return a list of superimposed images for the hex
     */
    public List<Image> supersFor(IHex hex) {
        return hexTileset.getSupers(hex, boardview);
    }

    /**
     * Return a list of orthographic images for the hex
     */
    public List<Image> orthoFor(IHex hex) {
        return hexTileset.getOrtho(hex, boardview);
    }

    public Image getMinefieldSign() {
        return minefieldSign;
    }

    public Image getNightFog() {
        return nightFog;
    }

    public Image getHexMask() {
        return hexMask;
    }

    public Set<String> getThemes() {
        return hexTileset.getThemes();
    }

    /**
     * Hexes affected by ECM will have a shaded static effect drawn on them.
     * This method will check the cache for a suitable static image for a given
     * color, and if one doesn't exists an image is created and cached.
     *
     * @param tint
     * @return
     */
    public Image getEcmStaticImage(Color tint) {
        Image image = ecmStaticImages.get(tint);
        if (image == null) {
            // Create a new hex-sized image
            image = new BufferedImage(HexTileset.HEX_W,
                    HexTileset.HEX_H, BufferedImage.TYPE_INT_ARGB);
            Graphics g = image.getGraphics();
            Polygon hexPoly = boardview.getHexPoly();
            g.setColor(tint.darker());
            // Draw ~200 small "ovals" at random locations within a a hex
            // A 3x3 oval ends up looking more like a cross
            for (int i = 0; i < 200; i++) {
                int x = (int)(Math.random() * HexTileset.HEX_W);
                int y = (int)(Math.random() * HexTileset.HEX_H);
                if (hexPoly.contains(x,y)) {
                    g.fillOval(x, y, 3, 3);
                }
            }
            ecmStaticImages.put(tint, image);
        }
        return image;
    }

    public Image getArtilleryTarget(int which) {
        switch (which) {
            case ARTILLERY_AUTOHIT:
                return artilleryAutohit;
            case ARTILLERY_ADJUSTED:
                return artilleryAdjusted;
            case ARTILLERY_INCOMING:
            default:
                return artilleryIncoming;
        }
    }

    /**
     * @return true if we're in the process of loading some images
     */
    public boolean isStarted() {
        return started;
    }

    /**
     * @return true if we're done loading images
     */
    public synchronized boolean isLoaded() {
        if (!loaded) {
            loaded = tracker.checkAll(true);
        }
        return started && loaded;
    }

    /**
     * Load all the images we'll need for the game and place them in the tracker
     */
    public void loadNeededImages(IGame game) {
        loaded = false;
        IBoard board = game.getBoard();
        // pre-match all hexes with images, load hex images
        int width = board.getWidth();
        int height = board.getHeight();
        // We want to cache as many of the images as we can, but if we have
        // more images than cache size, lets not waste time
        if ((width*height) > ImageCache.MAX_SIZE){
            // Find the largest size by size square we can fit in the cache
            int max_dim = (int)Math.sqrt(ImageCache.MAX_SIZE);
            if (width < max_dim) {
        	        height = (int)(ImageCache.MAX_SIZE / width);
            } else if (height < max_dim) {
        	        width = (int)(ImageCache.MAX_SIZE / height);
            } else {
                width = height = max_dim;
            }
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                IHex hex = board.getHex(x, y);
                loadHexImage(hex);
            }
        }

        // load all mech images
        for (Entity e : game.getEntitiesVector()) {
            if (e.getSecondaryPositions().isEmpty()) {
                loadImage(e, -1);
            } else {
                for (Integer secPos : e.getSecondaryPositions().keySet()) {
                    loadImage(e, secPos);
                }
            }

        }

        // load minefield sign
        minefieldSign = ImageUtil
                .loadImageFromFile(new MegaMekFile(Configuration.hexesDir(), Minefield.FILENAME_IMAGE).toString());
        if (minefieldSign.getWidth(null) <= 0 || minefieldSign.getHeight(null) <= 0) {
            System.out.println("Error opening minefield sign image!");
        }

        // load night overlay
        nightFog = ImageUtil
                .loadImageFromFile(new MegaMekFile(Configuration.hexesDir(), FILENAME_NIGHT_IMAGE).toString());
        if (nightFog.getWidth(null) <= 0 || nightFog.getHeight(null) <= 0) {
            System.out.println("Error opening nightFog image!");
        }

        // load the hexMask
        hexMask = ImageUtil.loadImageFromFile(new MegaMekFile(Configuration.hexesDir(), FILENAME_HEX_MASK).toString());
        if (hexMask.getWidth(null) <= 0 || hexMask.getHeight(null) <= 0) {
            System.out.println("Error opening hexMask image!");
        }

        // load artillery targets
        artilleryAutohit = ImageUtil.loadImageFromFile(
                new MegaMekFile(Configuration.hexesDir(), FILENAME_ARTILLERY_AUTOHIT_IMAGE).toString());
        if (artilleryAutohit.getWidth(null) <= 0 || artilleryAutohit.getHeight(null) <= 0) {
            System.out.println("Error opening artilleryAutohit image!");
        }

        artilleryAdjusted = ImageUtil.loadImageFromFile(
                new MegaMekFile(Configuration.hexesDir(), FILENAME_ARTILLERY_ADJUSTED_IMAGE).toString());
        if (artilleryAdjusted.getWidth(null) <= 0 || artilleryAdjusted.getHeight(null) <= 0) {
            System.out.println("Error opening artilleryAdjusted image!");
        }

        artilleryIncoming = ImageUtil.loadImageFromFile(
                new MegaMekFile(Configuration.hexesDir(), FILENAME_ARTILLERY_INCOMING_IMAGE).toString());
        if (artilleryIncoming.getWidth(null) <= 0 || artilleryIncoming.getHeight(null) <= 0) {
            System.out.println("Error opening artilleryIncoming image!");
        }
        
        started = true;
    }
    
    /** Loads the damage decal images. */
    private void loadDecals() {
        dmgLight = LoadDmgImage(Configuration.imagesDir(), FILENAME_DAMAGEDECAL_LIGHT);
        dmgModerate = LoadDmgImage(Configuration.imagesDir(), FILENAME_DAMAGEDECAL_MODERATE);
        dmgHeavy = LoadDmgImage(Configuration.imagesDir(), FILENAME_DAMAGEDECAL_HEAVY);
        dmgCrippled = LoadDmgImage(Configuration.imagesDir(), FILENAME_DAMAGEDECAL_CRIPPLED);
        
        SmokeSml = LoadDmgImage(Configuration.imagesDir(), FILE_SMOKE_SML);
        SmokeMed = LoadDmgImage(Configuration.imagesDir(), FILE_SMOKE_MED);
        SmokeLrg = LoadDmgImage(Configuration.imagesDir(), FILE_SMOKE_LRG);
        SmokeFireSml = LoadDmgImage(Configuration.imagesDir(), FILE_SMOKEFIRE_SML);
        SmokeFireMed = LoadDmgImage(Configuration.imagesDir(), FILE_SMOKEFIRE_MED);
        SmokeFireLrg = LoadDmgImage(Configuration.imagesDir(), FILE_SMOKEFIRE_LRG);
        
        dmgEmpty = LoadDmgImage(Configuration.imagesDir(), FILE_DAMAGEDECAL_EMPTY);
        
        decalLoaded = true;
    }
    
    /** Local method. Loads and returns the image. */ 
    private Image LoadDmgImage(File path, File file) {
        Image result = ImageUtil.loadImageFromFile(
                new MegaMekFile(path, file.toString()).toString());
        if (result.getWidth(null) <= 0 || result.getHeight(null) <= 0) {
            System.out.println("TilesetManager.LoadImage(): Error opening image "+file.toString()+"!");
        }
        return result;
    }

    public synchronized void reloadImage(Entity en) {
        if (en.getSecondaryPositions().isEmpty()) {
            loadImage(en, -1);
        } else {
            en.getSecondaryPositions().keySet().forEach(p -> loadImage(en, p));
        }
    }

    /**
     * Loads the image(s) for this hex into the tracker.
     *
     * @param hex the hex to load
     */
    private synchronized void loadHexImage(IHex hex) {
        hexTileset.assignMatch(hex, boardview);
        hexTileset.trackHexImages(hex, tracker);
    }

    /**
     * Removes the hex images from the cache.
     *
     * @param hex
     */
    public void clearHex(IHex hex) {
        hexTileset.clearHex(hex);
    }

    /**
     * Waits until a certain hex's images are done loading.
     *
     * @param hex the hex to wait for
     */
    public synchronized void waitForHex(IHex hex) {
        loadHexImage(hex);
        try {
            tracker.waitForID(1);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Loads all the hex tileset images
     */
    public synchronized void loadAllHexes() {
        hexTileset.loadAllImages(boardview, tracker);
    }

    /**
     *  Loads a preview image of the unit into the BufferedPanel.
     */
    public Image loadPreviewImage(Entity entity, Image camo, int tint, Component bp) {
        Image base = mechTileset.imageFor(entity, boardview, -1);
        EntityImage entityImage = new EntityImage(base, tint, camo, bp, entity);
        entityImage.loadFacings();
        Image preview = entityImage.getFacing(entity.getFacing());

        MediaTracker loadTracker = new MediaTracker(boardview);
        loadTracker.addImage(preview, 0);
        try {
            loadTracker.waitForID(0);
        } catch (InterruptedException e) {
            // should never come here

        }

        return preview;
    }

    /**
     * Get the camo pattern for the given player.
     *
     * @param player - the <code>Player</code> whose camo pattern is needed.
     * @return The <code>Image</code> of the player's camo pattern. This value
     *         will be <code>null</code> if the player has selected no camo
     *         pattern or if there was an error loading it.
     */
    public Image getPlayerCamo(IPlayer player) {

        // Return a null if the player has selected no camo file.
        if ((null == player.getCamoCategory())
                || IPlayer.NO_CAMO.equals(player.getCamoCategory())) {
            return null;
        }

        // Try to get the player's camo file.
        Image camo = null;
        try {

            // Translate the root camo directory name.
            String category = player.getCamoCategory();
            if (IPlayer.ROOT_CAMO.equals(category)) {
                category = ""; //$NON-NLS-1$
            }
            camo = (Image) camos.getItem(category, player.getCamoFileName());

        } catch (Exception err) {
            err.printStackTrace();
        }
        return camo;
    }

    /**
     * Get the camo pattern for the given player.
     *
     * @param entity - the <code>Entity</code> whose camo pattern is needed.
     * @return The <code>Image</code> of the player's camo pattern. This value
     *         will be <code>null</code> if the player has selected no camo
     *         pattern or if there was an error loading it.
     */
    public Image getEntityCamo(Entity entity) {
        // Return a null if the player has selected no camo file.
        if ((null == entity.getCamoCategory())
                || IPlayer.NO_CAMO.equals(entity.getCamoCategory())) {
            return null;
        }

        // Try to get the player's camo file.
        Image camo = null;
        try {
            // Translate the root camo directory name.
            String category = entity.getCamoCategory();
            if (IPlayer.ROOT_CAMO.equals(category)) {
                category = ""; //$NON-NLS-1$
            }
            camo = (Image) camos.getItem(category, entity.getCamoFileName());
        } catch (Exception err) {
            err.printStackTrace();
        }
        return camo;
    }

    /**
     * Load a single entity image
     */
    public synchronized void loadImage(Entity entity, int secondaryPos) {
        Image base = mechTileset.imageFor(entity, boardview, secondaryPos);
        Image wreck = wreckTileset.imageFor(entity, boardview, secondaryPos);

        IPlayer player = entity.getOwner();
        int tint = PlayerColors.getColorRGB(player.getColorIndex());

        Image camo = null;
        if (getEntityCamo(entity) != null) {
            camo = getEntityCamo(entity);
        } else {
            camo = getPlayerCamo(player);
        }
        EntityImage entityImage = null;

        // check if we have a duplicate image already loaded
        for (Iterator<EntityImage> j = mechImageList.iterator(); j.hasNext();) {
            EntityImage onList = j.next();
            if ((onList.getBase() != null) && onList.getBase().equals(base)
                    && (onList.tint == tint) && (onList.getCamo() != null)
                    && onList.getCamo().equals(camo) && onList.getDmgLvl() == entity.getDamageLevel()) {
                entityImage = onList;
                break;
            }
        }

        // if we don't have a cached image, make a new one
        if (entityImage == null) {
            entityImage = new EntityImage(base, wreck, tint, camo, boardview, entity, secondaryPos);
            mechImageList.add(entityImage);
            entityImage.loadFacings();
            for (int j = 0; j < 6; j++) {
                tracker.addImage(entityImage.getFacing(j), 1);
            }
        }

        // relate this id to this image set
        ArrayList<Integer> temp = new ArrayList<Integer>();
        temp.add(entity.getId());
        temp.add(secondaryPos);
        mechImages.put(temp, entityImage);
    }

    /**
     * Resets the started and loaded flags
     */
    public synchronized void reset() {
        loaded = false;
        started = false;

        tracker = new MediaTracker(boardview);
        mechImageList.clear();
        mechImages.clear();
        hexTileset.clearAllHexes();
    }

    /**
     * A class to handle the image permutations for an entity
     */
    private class EntityImage {
        private Image base;
        private Image wreck;
        
        /** A smaller icon used for the unit overview. */
        private Image icon;
        /** A color used instead of a camo. */
        int tint;
        private Image camo;
        private Image[] facings = new Image[6];
        private Image[] wreckFacings = new Image[6];
        private Component parent;
        /** The damage level, from none to crippled. */
        private int dmgLevel;
        /** The tonnage of the unit. */
        private double weight;
        /** True for units of class or subclass of Infantry. */
        private boolean isInfantry;
        /** True when the image is for an additional hex of multi-hex units. */
        private boolean isSecondaryPos;
        /** True when the image is for the lobby. */
        private boolean isPreview;

        private final int IMG_WIDTH = HexTileset.HEX_W;
        private final int IMG_HEIGHT = HexTileset.HEX_H;
        private final int IMG_SIZE = IMG_WIDTH * IMG_HEIGHT;

        public EntityImage(Image base, int tint, Image camo, Component comp, Entity entity) {
            this(base, null, tint, camo, comp, entity, -1, true);
        }
        
        public EntityImage(Image base, Image wreck, int tint, Image camo,
                Component comp, Entity entity, int secondaryPos) {
            this(base, null, tint, camo, comp, entity, secondaryPos, false);
        }
        
        public EntityImage(Image base, Image wreck, int tint, Image camo,
                Component comp, Entity entity, int secondaryPos, boolean preview) {
            this.base = base;
            this.tint = tint;
            this.camo = camo;
            parent = comp;
            this.wreck = wreck;
            this.dmgLevel = entity.getDamageLevel();
            this.weight = entity.getWeight();
            isInfantry = entity instanceof Infantry;
            isSecondaryPos = secondaryPos != 0 && secondaryPos != -1;
            isPreview = preview;
        }

        public Image getCamo() {
            return camo;
        }
        
        public int getDmgLvl() {
            return dmgLevel;
        }

        public void loadFacings() {
            if (base == null) {
                return;
            }
            
            // Apply the camo and damage decal (if not infantry)
            base = applyColor(base);
            if (!isInfantry && !isSecondaryPos && !isPreview) {
                base = applyDamageDecal(base);
            }

            // Save a small icon for the unit overview
            icon = ImageUtil.getScaledImage(base,  56, 48);
            
            // Generate rotated images for the unit and for a wreck
            for (int i = 0; i < 6; i++) {
                facings[i] = rotateImage(base, i);
            }

            if (wreck != null) {
                wreck = applyColor(wreck);
                for (int i = 0; i < 6; i++) {
                    wreckFacings[i] = rotateImage(wreck, i);
                }
            }
        }
        
        /** Rotates a given unit image towards direction dir. */
        private BufferedImage rotateImage(Image img, int dir) {
            double cx = base.getWidth(parent) / 2.0;
            double cy = base.getHeight(parent) / 2.0;
            AffineTransformOp xform = new AffineTransformOp(
                    AffineTransform.getRotateInstance(
                            (-Math.PI / 3) * (6 - dir), cx, cy),
                    AffineTransformOp.TYPE_BICUBIC);
            BufferedImage src;
            if (img instanceof BufferedImage) {
                src = (BufferedImage) img;
            } else {
                src = ImageUtil.createAcceleratedImage(img);
            }
            BufferedImage dst = ImageUtil.createAcceleratedImage(
                    src.getWidth(), src.getHeight());
            xform.filter(src, dst);
            return dst;
        }

        @SuppressWarnings("unused")
        public Image loadPreviewImage() {
            base = applyColor(base);
            return base;
        }

        public Image getFacing(int facing) {
            return facings[facing];
        }

        public Image getWreckFacing(int facing) {
            return wreckFacings[facing];
        }

        public Image getBase() {
            return base;
        }

        public Image getIcon() {
            return icon;
        }

        private Image applyColor(Image image) {
            if (image == null) {
                return null;
            }
            boolean useCamo = (camo != null);
            
            // Prepare the images for access
            int[] pMech = new int[IMG_SIZE];
            int[] pCamo = new int[IMG_SIZE];
            try {
                grabImagePixels(image, pMech);
                if (useCamo) {
                    grabImagePixels(camo, pCamo);
                }
            } catch (Exception e) {
                System.err.println("TilesetManager.EntityImage: " //$NON-NLS-1$
                        + "Failed to grab pixels for image. " //$NON-NLS-1$
                        + e.getMessage());
                return image;
            }

            // Overlay the camo or color  
            for (int i = 0; i < IMG_SIZE; i++) {
                int pixel = pMech[i];
                int alpha = (pixel >> 24) & 0xff;
                int red = (pixel >> 16) & 0xff;
                int green = (pixel >> 8) & 0xff;
                int blue = (pixel) & 0xff;
                
                // Don't apply the camo over colored (not gray) pixels
                if (!(red == green && green == blue)) {
                    continue;
                }
                
                // Apply the camo only on the icon pixels, not on transparent pixels
                if (alpha != 0) {
                    int pixel1 = useCamo ? pCamo[i] : tint;
                    int red1 = (pixel1 >> 16) & 0xff;
                    int green1 = (pixel1 >> 8) & 0xff;
                    int blue1 = (pixel1) & 0xff;

                    int red2 = red1 * blue / 255;
                    int green2 = green1 * blue / 255;
                    int blue2 = blue1 * blue / 255;

                    pMech[i] = (alpha << 24) | (red2 << 16) | (green2 << 8) | blue2;
                }
            }
            
            Image result = parent.createImage(new MemoryImageSource(IMG_WIDTH,
                    IMG_HEIGHT, pMech, 0, IMG_WIDTH));
            return ImageUtil.createAcceleratedImage(result);
        }
        
        /** Applies decal images based on the damage and weight of the unit. */
        private Image applyDamageDecal(Image image) {
            if (image == null) {
                return null;
            }
            
            if (!decalLoaded) {
                loadDecals();
            }

            // Get the damage decal; will be null for undamaged
            Image dmgDecal = getDamageDecal();
            if (dmgDecal == null) {
                return image;
            }
            
            // Get the smoke image for heavier damage; is transparent for lighter damage
            Image smokeImg = getSmokeOverlay();
            if (smokeImg == null) {
                System.err.println("TilesetManager.EntityImage: " //$NON-NLS-1$
                        + "Smoke decal image is null."); //$NON-NLS-1$
                return image;
            }

            // Prepare the images for access
            int[] pUnit = new int[IMG_SIZE];
            int[] pDmgD = new int[IMG_SIZE];
            try {
                grabImagePixels(image, pUnit);
                grabImagePixels(dmgDecal, pDmgD);
            } catch (Exception e) {
                System.err.println("TilesetManager.EntityImage: " //$NON-NLS-1$
                        + "Failed to grab pixels for image. " //$NON-NLS-1$
                        + e.getMessage());
                return image;
            }

            // Overlay the damage decal where the unit image 
            // is not transparent
            for (int i = 0; i < IMG_SIZE; i++) {
                int alp = (pUnit[i] >> 24) & 0xff;
                int alpD = (pDmgD[i] >> 24) & 0xff;
                
                if (alp != 0 && alpD != 0) {
                    int red = (pUnit[i] >> 16) & 0xff;
                    int grn = (pUnit[i] >> 8) & 0xff;
                    int blu = (pUnit[i]) & 0xff;
                    int redD = (pDmgD[i] >> 16) & 0xff;
                    int grnD = (pDmgD[i] >> 8) & 0xff;
                    int bluD = (pDmgD[i]) & 0xff;

                    red = Math.min(255, (red * (255 - alpD) + redD * alpD ) / 255);
                    grn = Math.min(255, (grn * (255 - alpD) + grnD * alpD ) / 255);
                    blu = Math.min(255, (blu * (255 - alpD) + bluD * alpD ) / 255);
                    
                    pUnit[i] = (alp << 24) | (red << 16) | (grn << 8) | blu;
                }
            }
            
            Image temp = parent.createImage(new MemoryImageSource(IMG_WIDTH,
                    IMG_HEIGHT, pUnit, 0, IMG_WIDTH));
            Image result = ImageUtil.createAcceleratedImage(temp);
            
            // Overlay the smoke image
            Graphics g = result.getGraphics();
            g.drawImage(smokeImg, 0, 0, null);
            
            return result;
        }
        
        private void grabImagePixels(Image img, int[] pixels) 
        throws InterruptedException, RuntimeException {
            PixelGrabber pg = new PixelGrabber(img, 0, 0, IMG_WIDTH,
                    IMG_HEIGHT, pixels, 0, IMG_WIDTH);
            pg.grabPixels();
            if ((pg.getStatus() & ImageObserver.ABORT) != 0) {
                throw new RuntimeException("ImageObserver aborted.");
            }
        }
        
        /** Returns the smoke overlay or a transparent image based on damage level and weight. */
        private Image getSmokeOverlay() {
            if (dmgLevel == Entity.DMG_NONE 
                    || dmgLevel == Entity.DMG_LIGHT
                    || dmgLevel == Entity.DMG_MODERATE) {
                return dmgEmpty;
            }
            
            if (weight > 70) {
                return dmgLevel == Entity.DMG_HEAVY ? SmokeLrg : SmokeFireLrg;
            } else if (weight > 40) {
                return dmgLevel == Entity.DMG_HEAVY ? SmokeMed : SmokeFireMed;
            } else {
                return dmgLevel == Entity.DMG_HEAVY ? SmokeSml : SmokeFireSml;
            }
        }

        /** Returns the damage decal based on damage level. */
        private Image getDamageDecal() {
            switch (dmgLevel) {
            case Entity.DMG_LIGHT:
                return dmgLight;
            case Entity.DMG_MODERATE:
                return dmgModerate;
            case Entity.DMG_HEAVY:
                return dmgHeavy;
            case Entity.DMG_CRIPPLED:
                return dmgCrippled;
            default: // DMG_NONE:
                return null;
            }
        }
    }

    public static void createDefaultHexSet(){
        try {
            FileOutputStream fos = new FileOutputStream(new MegaMekFile(Configuration.hexesDir(), FILENAME_DEFAULT_HEX_SET).getFile());
            PrintStream p = new PrintStream(fos);

            p.println("# suggested hex tileset");
            p.println("#");
            p.println("# format is:");
            p.println("# base/super/ortho <elevation> <terrains> <theme> <image>");
            p.println("#");
            p.println("");
            p.println("ortho * \"bridge:*:00;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_00.gif\"");
            p.println("ortho * \"bridge:*:01;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_01.gif\"");
            p.println("ortho * \"bridge:*:02;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_02.gif\"");
            p.println("ortho * \"bridge:*:03;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_03.gif\"");
            p.println("ortho * \"bridge:*:04;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_04.gif\"");
            p.println("ortho * \"bridge:*:05;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_05.gif\"");
            p.println("ortho * \"bridge:*:06;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_06.gif\"");
            p.println("ortho * \"bridge:*:07;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_07.gif\"");
            p.println("ortho * \"bridge:*:08;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_08.gif\"");
            p.println("ortho * \"bridge:*:09;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_09.gif\"");
            p.println("ortho * \"bridge:*:10;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_10.gif\"");
            p.println("ortho * \"bridge:*:11;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_11.gif\"");
            p.println("ortho * \"bridge:*:12;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_12.gif\"");
            p.println("ortho * \"bridge:*:13;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_13.gif\"");
            p.println("ortho * \"bridge:*:14;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_14.gif\"");
            p.println("ortho * \"bridge:*:15;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_15.gif\"");
            p.println("ortho * \"bridge:*:16;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_16.gif\"");
            p.println("ortho * \"bridge:*:17;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_17.gif\"");
            p.println("ortho * \"bridge:*:18;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_18.gif\"");
            p.println("ortho * \"bridge:*:19;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_19.gif\"");
            p.println("ortho * \"bridge:*:20;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_20.gif\"");
            p.println("ortho * \"bridge:*:21;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_21.gif\"");
            p.println("ortho * \"bridge:*:22;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_22.gif\"");
            p.println("ortho * \"bridge:*:23;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_23.gif\"");
            p.println("ortho * \"bridge:*:24;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_24.gif\"");
            p.println("ortho * \"bridge:*:25;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_25.gif\"");
            p.println("ortho * \"bridge:*:26;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_26.gif\"");
            p.println("ortho * \"bridge:*:27;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_27.gif\"");
            p.println("ortho * \"bridge:*:28;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_28.gif\"");
            p.println("ortho * \"bridge:*:29;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_29.gif\"");
            p.println("ortho * \"bridge:*:30;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_30.gif\"");
            p.println("ortho * \"bridge:*:31;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_31.gif\"");
            p.println("ortho * \"bridge:*:32;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_32.gif\"");
            p.println("ortho * \"bridge:*:33;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_33.gif\"");
            p.println("ortho * \"bridge:*:34;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_34.gif\"");
            p.println("ortho * \"bridge:*:35;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_35.gif\"");
            p.println("ortho * \"bridge:*:36;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_36.gif\"");
            p.println("ortho * \"bridge:*:37;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_37.gif\"");
            p.println("ortho * \"bridge:*:38;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_38.gif\"");
            p.println("ortho * \"bridge:*:39;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_39.gif\"");
            p.println("ortho * \"bridge:*:40;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_40.gif\"");
            p.println("ortho * \"bridge:*:41;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_41.gif\"");
            p.println("ortho * \"bridge:*:42;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_42.gif\"");
            p.println("ortho * \"bridge:*:43;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_43.gif\"");
            p.println("ortho * \"bridge:*:44;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_44.gif\"");
            p.println("ortho * \"bridge:*:45;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_45.gif\"");
            p.println("ortho * \"bridge:*:46;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_46.gif\"");
            p.println("ortho * \"bridge:*:47;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_47.gif\"");
            p.println("ortho * \"bridge:*:48;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_48.gif\"");
            p.println("ortho * \"bridge:*:49;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_49.gif\"");
            p.println("ortho * \"bridge:*:50;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_50.gif\"");
            p.println("ortho * \"bridge:*:51;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_51.gif\"");
            p.println("ortho * \"bridge:*:52;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_52.gif\"");
            p.println("ortho * \"bridge:*:53;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_53.gif\"");
            p.println("ortho * \"bridge:*:54;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_54.gif\"");
            p.println("ortho * \"bridge:*:55;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_55.gif\"");
            p.println("ortho * \"bridge:*:56;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_56.gif\"");
            p.println("ortho * \"bridge:*:57;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_57.gif\"");
            p.println("ortho * \"bridge:*:58;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_58.gif\"");
            p.println("ortho * \"bridge:*:59;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_59.gif\"");
            p.println("ortho * \"bridge:*:60;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_60.gif\"");
            p.println("ortho * \"bridge:*:61;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_61.gif\"");
            p.println("ortho * \"bridge:*:62;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_62.gif\"");
            p.println("ortho * \"bridge:*:63;bridge_elev:*;bridge_cf:*\" \"\" \"bridge/bridge_63.gif\"");
            p.println("");
            p.println("super * \"elevator:10\" \"\" \"boring/elevator1.gif\"");
            p.println("super * \"elevator:2\" \"\" \"boring/elevator2.gif\"");
            p.println("");
            p.println("super * \"geyser:1\" \"\" \"boring/geyservent.gif\"");
            p.println("super * \"geyser:2\" \"\" \"boring/geysererupt.gif\"");
            p.println("super * \"geyser:3\" \"\" \"boring/geyservent.gif\"");
            p.println("");
            p.println("super * \"road:1:00\" \"\" \"boring/road00.gif\"");
            p.println("super * \"road:1:01\" \"\" \"boring/road01.gif\"");
            p.println("super * \"road:1:02\" \"\" \"boring/road02.gif\"");
            p.println("super * \"road:1:03\" \"\" \"boring/road03.gif\"");
            p.println("super * \"road:1:04\" \"\" \"boring/road04.gif\"");
            p.println("super * \"road:1:05\" \"\" \"boring/road05.gif\"");
            p.println("super * \"road:1:06\" \"\" \"boring/road06.gif\"");
            p.println("super * \"road:1:07\" \"\" \"boring/road07.gif\"");
            p.println("super * \"road:1:08\" \"\" \"boring/road08.gif\"");
            p.println("super * \"road:1:09\" \"\" \"boring/road09.gif\"");
            p.println("super * \"road:1:10\" \"\" \"boring/road10.gif\"");
            p.println("super * \"road:1:11\" \"\" \"boring/road11.gif\"");
            p.println("super * \"road:1:12\" \"\" \"boring/road12.gif\"");
            p.println("super * \"road:1:13\" \"\" \"boring/road13.gif\"");
            p.println("super * \"road:1:14\" \"\" \"boring/road14.gif\"");
            p.println("super * \"road:1:15\" \"\" \"boring/road15.gif\"");
            p.println("super * \"road:1:16\" \"\" \"boring/road16.gif\"");
            p.println("super * \"road:1:17\" \"\" \"boring/road17.gif\"");
            p.println("super * \"road:1:18\" \"\" \"boring/road18.gif\"");
            p.println("super * \"road:1:19\" \"\" \"boring/road19.gif\"");
            p.println("super * \"road:1:20\" \"\" \"boring/road20.gif\"");
            p.println("super * \"road:1:21\" \"\" \"boring/road21.gif\"");
            p.println("super * \"road:1:22\" \"\" \"boring/road22.gif\"");
            p.println("super * \"road:1:23\" \"\" \"boring/road23.gif\"");
            p.println("super * \"road:1:24\" \"\" \"boring/road24.gif\"");
            p.println("super * \"road:1:25\" \"\" \"boring/road25.gif\"");
            p.println("super * \"road:1:26\" \"\" \"boring/road26.gif\"");
            p.println("super * \"road:1:27\" \"\" \"boring/road27.gif\"");
            p.println("super * \"road:1:28\" \"\" \"boring/road28.gif\"");
            p.println("super * \"road:1:29\" \"\" \"boring/road29.gif\"");
            p.println("super * \"road:1:30\" \"\" \"boring/road30.gif\"");
            p.println("super * \"road:1:31\" \"\" \"boring/road31.gif\"");
            p.println("super * \"road:1:32\" \"\" \"boring/road32.gif\"");
            p.println("super * \"road:1:33\" \"\" \"boring/road33.gif\"");
            p.println("super * \"road:1:34\" \"\" \"boring/road34.gif\"");
            p.println("super * \"road:1:35\" \"\" \"boring/road35.gif\"");
            p.println("super * \"road:1:36\" \"\" \"boring/road36.gif\"");
            p.println("super * \"road:1:37\" \"\" \"boring/road37.gif\"");
            p.println("super * \"road:1:38\" \"\" \"boring/road38.gif\"");
            p.println("super * \"road:1:39\" \"\" \"boring/road39.gif\"");
            p.println("super * \"road:1:40\" \"\" \"boring/road40.gif\"");
            p.println("super * \"road:1:41\" \"\" \"boring/road41.gif\"");
            p.println("super * \"road:1:42\" \"\" \"boring/road42.gif\"");
            p.println("super * \"road:1:43\" \"\" \"boring/road43.gif\"");
            p.println("super * \"road:1:44\" \"\" \"boring/road44.gif\"");
            p.println("super * \"road:1:45\" \"\" \"boring/road45.gif\"");
            p.println("super * \"road:1:46\" \"\" \"boring/road46.gif\"");
            p.println("super * \"road:1:47\" \"\" \"boring/road47.gif\"");
            p.println("super * \"road:1:48\" \"\" \"boring/road48.gif\"");
            p.println("super * \"road:1:49\" \"\" \"boring/road49.gif\"");
            p.println("super * \"road:1:50\" \"\" \"boring/road50.gif\"");
            p.println("super * \"road:1:51\" \"\" \"boring/road51.gif\"");
            p.println("super * \"road:1:52\" \"\" \"boring/road52.gif\"");
            p.println("super * \"road:1:53\" \"\" \"boring/road53.gif\"");
            p.println("super * \"road:1:54\" \"\" \"boring/road54.gif\"");
            p.println("super * \"road:1:55\" \"\" \"boring/road55.gif\"");
            p.println("super * \"road:1:56\" \"\" \"boring/road56.gif\"");
            p.println("super * \"road:1:57\" \"\" \"boring/road57.gif\"");
            p.println("super * \"road:1:58\" \"\" \"boring/road58.gif\"");
            p.println("super * \"road:1:59\" \"\" \"boring/road59.gif\"");
            p.println("super * \"road:1:60\" \"\" \"boring/road60.gif\"");
            p.println("super * \"road:1:61\" \"\" \"boring/road61.gif\"");
            p.println("super * \"road:1:62\" \"\" \"boring/road62.gif\"");
            p.println("super * \"road:1:63\" \"\" \"boring/road63.gif\"");
            p.println("");
            p.println("super * \"fluff:5:00\" \"\" \"fluff/cars_1.gif\"");
            p.println("super * \"fluff:5:01\" \"\" \"fluff/cars_2.gif\"");
            p.println("super * \"fluff:5:02\" \"\" \"fluff/cars_3.gif\"");
            p.println("super * \"fluff:5:03\" \"\" \"fluff/cars_4.gif\"");
            p.println("super * \"fluff:5:04\" \"\" \"fluff/cars_5.gif\"");
            p.println("super * \"fluff:5:05\" \"\" \"fluff/cars_6.gif\"");
            p.println("super * \"fluff:5:06\" \"\" \"fluff/cars_7.gif\"");
            p.println("super * \"fluff:5:07\" \"\" \"fluff/cars_8.gif\"");
            p.println("");
            p.println("super * \"fluff:4:00\" \"\" \"fluff/square1.gif\"");
            p.println("super * \"fluff:4:01\" \"\" \"fluff/square2.gif\"");
            p.println("super * \"fluff:4:02\" \"\" \"fluff/square3.gif\"");
            p.println("super * \"fluff:4:03\" \"\" \"fluff/square4.gif\"");
            p.println("super * \"fluff:4:04\" \"\" \"fluff/square5.gif\"");
            p.println("super * \"fluff:4:05\" \"\" \"fluff/square6.gif\"");
            p.println("super * \"fluff:4:06\" \"\" \"fluff/pillars1.gif\"");
            p.println("super * \"fluff:4:07\" \"\" \"fluff/pillars2.gif\"");
            p.println("super * \"fluff:4:08\" \"\" \"fluff/pillars3.gif\"");
            p.println("super * \"fluff:4:09\" \"\" \"fluff/pillars4.gif\"");
            p.println("super * \"fluff:4:10\" \"\" \"fluff/pillars5.gif\"");
            p.println("super * \"fluff:4:11\" \"\" \"fluff/pillars6.gif\"");
            p.println("");
            p.println("super * \"fluff:7:00\" \"\" \"fluff/construction1.gif\"");
            p.println("super * \"fluff:7:01\" \"\" \"fluff/construction2.gif\"");
            p.println("super * \"fluff:7:02\" \"\" \"fluff/construction3.gif\"");
            p.println("super * \"fluff:7:03\" \"\" \"fluff/suburb1.gif\"");
            p.println("super * \"fluff:7:04\" \"\" \"fluff/suburb2.gif\"");
            p.println("super * \"fluff:7:05\" \"\" \"fluff/suburb3.gif\"");
            p.println("");
            p.println("super * \"fluff:8:06\" \"\" \"fluff/garden1.gif\"");
            p.println("super * \"fluff:8:07\" \"\" \"fluff/garden2.gif\"");
            p.println("super * \"fluff:8:08\" \"\" \"fluff/garden3.gif\"");
            p.println("super * \"fluff:8:09\" \"\" \"fluff/garden4.gif\"");
            p.println("super * \"fluff:8:10\" \"\" \"fluff/garden5.gif\"");
            p.println("super * \"fluff:8:11\" \"\" \"fluff/garden6.gif\"");
            p.println("");
            p.println("super * \"fluff:9:00\" \"\" \"fluff/maglevtrack1.gif\"");
            p.println("super * \"fluff:9:01\" \"\" \"fluff/maglevtrack2.gif\"");
            p.println("super * \"fluff:9:02\" \"\" \"fluff/maglevtrack3.gif\"");
            p.println("super * \"fluff:9:03\" \"\" \"fluff/maglevstation1.gif\"");
            p.println("super * \"fluff:9:04\" \"\" \"fluff/maglevstation2.gif\"");
            p.println("super * \"fluff:9:05\" \"\" \"fluff/maglevstation3.gif\"");
            p.println("super * \"fluff:9:06\" \"\" \"fluff/maglevtrain1.gif\"");
            p.println("super * \"fluff:9:07\" \"\" \"fluff/maglevtrain2.gif\"");
            p.println("super * \"fluff:9:08\" \"\" \"fluff/maglevtrain3.gif\"");
            p.println("super * \"fluff:9:09\" \"\" \"fluff/maglevtrain4.gif\"");
            p.println("super * \"fluff:9:10\" \"\" \"fluff/maglevtrain5.gif\"");
            p.println("super * \"fluff:9:11\" \"\" \"fluff/maglevtrain6.gif\"");
            p.println("");
            p.println("super * \"road:2:00\" \"\" \"fluff/road_trees_00.gif\"");
            p.println("super * \"road:2:01\" \"\" \"fluff/road_trees_01.gif\"");
            p.println("super * \"road:2:02\" \"\" \"fluff/road_trees_02.gif\"");
            p.println("super * \"road:2:03\" \"\" \"fluff/road_trees_03.gif\"");
            p.println("super * \"road:2:04\" \"\" \"fluff/road_trees_04.gif\"");
            p.println("super * \"road:2:05\" \"\" \"fluff/road_trees_05.gif\"");
            p.println("super * \"road:2:06\" \"\" \"fluff/road_trees_06.gif\"");
            p.println("super * \"road:2:07\" \"\" \"fluff/road_trees_07.gif\"");
            p.println("super * \"road:2:08\" \"\" \"fluff/road_trees_08.gif\"");
            p.println("super * \"road:2:09\" \"\" \"fluff/road_trees_09.gif\"");
            p.println("super * \"road:2:10\" \"\" \"fluff/road_trees_10.gif\"");
            p.println("super * \"road:2:11\" \"\" \"fluff/road_trees_11.gif\"");
            p.println("super * \"road:2:12\" \"\" \"fluff/road_trees_12.gif\"");
            p.println("super * \"road:2:13\" \"\" \"fluff/road_trees_13.gif\"");
            p.println("super * \"road:2:14\" \"\" \"fluff/road_trees_14.gif\"");
            p.println("super * \"road:2:15\" \"\" \"fluff/road_trees_15.gif\"");
            p.println("super * \"road:2:16\" \"\" \"fluff/road_trees_16.gif\"");
            p.println("super * \"road:2:17\" \"\" \"fluff/road_trees_17.gif\"");
            p.println("super * \"road:2:18\" \"\" \"fluff/road_trees_18.gif\"");
            p.println("super * \"road:2:19\" \"\" \"fluff/road_trees_19.gif\"");
            p.println("super * \"road:2:20\" \"\" \"fluff/road_trees_20.gif\"");
            p.println("super * \"road:2:21\" \"\" \"fluff/road_trees_21.gif\"");
            p.println("super * \"road:2:22\" \"\" \"fluff/road_trees_22.gif\"");
            p.println("super * \"road:2:23\" \"\" \"fluff/road_trees_23.gif\"");
            p.println("super * \"road:2:24\" \"\" \"fluff/road_trees_24.gif\"");
            p.println("super * \"road:2:25\" \"\" \"fluff/road_trees_25.gif\"");
            p.println("super * \"road:2:26\" \"\" \"fluff/road_trees_26.gif\"");
            p.println("super * \"road:2:27\" \"\" \"fluff/road_trees_27.gif\"");
            p.println("super * \"road:2:28\" \"\" \"fluff/road_trees_28.gif\"");
            p.println("super * \"road:2:29\" \"\" \"fluff/road_trees_29.gif\"");
            p.println("super * \"road:2:30\" \"\" \"fluff/road_trees_30.gif\"");
            p.println("super * \"road:2:31\" \"\" \"fluff/road_trees_31.gif\"");
            p.println("super * \"road:2:32\" \"\" \"fluff/road_trees_32.gif\"");
            p.println("super * \"road:2:33\" \"\" \"fluff/road_trees_33.gif\"");
            p.println("super * \"road:2:34\" \"\" \"fluff/road_trees_34.gif\"");
            p.println("super * \"road:2:35\" \"\" \"fluff/road_trees_35.gif\"");
            p.println("super * \"road:2:36\" \"\" \"fluff/road_trees_36.gif\"");
            p.println("super * \"road:2:37\" \"\" \"fluff/road_trees_37.gif\"");
            p.println("super * \"road:2:38\" \"\" \"fluff/road_trees_38.gif\"");
            p.println("super * \"road:2:39\" \"\" \"fluff/road_trees_39.gif\"");
            p.println("super * \"road:2:40\" \"\" \"fluff/road_trees_40.gif\"");
            p.println("super * \"road:2:41\" \"\" \"fluff/road_trees_41.gif\"");
            p.println("super * \"road:2:42\" \"\" \"fluff/road_trees_42.gif\"");
            p.println("super * \"road:2:43\" \"\" \"fluff/road_trees_43.gif\"");
            p.println("super * \"road:2:44\" \"\" \"fluff/road_trees_44.gif\"");
            p.println("super * \"road:2:45\" \"\" \"fluff/road_trees_45.gif\"");
            p.println("super * \"road:2:46\" \"\" \"fluff/road_trees_46.gif\"");
            p.println("super * \"road:2:47\" \"\" \"fluff/road_trees_47.gif\"");
            p.println("super * \"road:2:48\" \"\" \"fluff/road_trees_48.gif\"");
            p.println("super * \"road:2:49\" \"\" \"fluff/road_trees_49.gif\"");
            p.println("super * \"road:2:50\" \"\" \"fluff/road_trees_50.gif\"");
            p.println("super * \"road:2:51\" \"\" \"fluff/road_trees_51.gif\"");
            p.println("super * \"road:2:52\" \"\" \"fluff/road_trees_52.gif\"");
            p.println("super * \"road:2:53\" \"\" \"fluff/road_trees_53.gif\"");
            p.println("super * \"road:2:54\" \"\" \"fluff/road_trees_54.gif\"");
            p.println("super * \"road:2:55\" \"\" \"fluff/road_trees_55.gif\"");
            p.println("super * \"road:2:56\" \"\" \"fluff/road_trees_56.gif\"");
            p.println("super * \"road:2:57\" \"\" \"fluff/road_trees_57.gif\"");
            p.println("super * \"road:2:58\" \"\" \"fluff/road_trees_58.gif\"");
            p.println("super * \"road:2:59\" \"\" \"fluff/road_trees_59.gif\"");
            p.println("super * \"road:2:60\" \"\" \"fluff/road_trees_60.gif\"");
            p.println("super * \"road:2:61\" \"\" \"fluff/road_trees_61.gif\"");
            p.println("super * \"road:2:62\" \"\" \"fluff/road_trees_62.gif\"");
            p.println("super * \"road:2:63\" \"\" \"fluff/road_trees_63.gif\"");
            p.println("");
            p.println("super * \"building:1;bldg_elev:1;bldg_cf:*;fluff:11:1\" \"\" \"boring/cropped_farm.png\"");
            p.println("super * \"building:1;bldg_elev:2;bldg_cf:*;fluff:11:2\" \"\" \"boring/cropped_church.png\"");
            p.println("super * \"building:1;bldg_elev:3;bldg_cf:*;fluff:11:3\" \"\" \"boring/light_bldg.png\"");
            p.println("super * \"building:2;bldg_elev:3;bldg_cf:*;fluff:11:4\" \"\" \"boring/cropped_cannon_tower.png\"");
            p.println("super * \"building:3;bldg_elev:3;bldg_cf:*;fluff:11:5\" \"\" \"boring/cropped_refinery.png\"");
            p.println("super * \"building:3;bldg_elev:4;bldg_cf:*;fluff:11:6\" \"\" \"boring/cropped_mage_tower.png\"");
            p.println("");
            p.println("super * \"building:4;bldg_elev:1;bldg_cf:*\" \"\" \"singlehex/hardened_1.gif\"");
            p.println("super * \"building:4;bldg_elev:2;bldg_cf:*\" \"\" \"singlehex/hardened_2.gif\"");
            p.println("super * \"building:4;bldg_elev:3;bldg_cf:*\" \"\" \"singlehex/hardened_3.gif\"");
            p.println("super * \"building:4;bldg_elev:4;bldg_cf:*\" \"\" \"singlehex/hardened_4.gif\"");
            p.println("");
            p.println("super * \"building:3;bldg_elev:1;bldg_cf:*\" \"\" \"singlehex/heavy_1.gif\"");
            p.println("super * \"building:3;bldg_elev:2;bldg_cf:*\" \"\" \"singlehex/heavy_2.gif\"");
            p.println("super * \"building:3;bldg_elev:3;bldg_cf:*\" \"\" \"singlehex/heavy_3.gif\"");
            p.println("super * \"building:3;bldg_elev:4;bldg_cf:*\" \"\" \"singlehex/heavy_4.gif\"");
            p.println("");
            p.println("super * \"building:2;bldg_elev:1;bldg_cf:*\" \"\" \"singlehex/medium_1.gif\"");
            p.println("super * \"building:2;bldg_elev:2;bldg_cf:*\" \"\" \"singlehex/medium_2.gif\"");
            p.println("super * \"building:2;bldg_elev:3;bldg_cf:*\" \"\" \"singlehex/medium_3.gif\"");
            p.println("super * \"building:2;bldg_elev:4;bldg_cf:*\" \"\" \"singlehex/medium_4.gif\"");
            p.println("");
            p.println("super * \"building:1;bldg_elev:*;bldg_cf:*;fluff:3\"     \"\" \"singlehex/light_3.gif\"");
            p.println("super * \"building:1;bldg_elev:*;bldg_cf:*;fluff:1\"     \"\" \"singlehex/light_5.gif\"");
            p.println("super * \"building:1;bldg_elev:1;bldg_cf:*\" \"\" \"singlehex/light_6.gif\"");
            p.println("super * \"building:1;bldg_elev:2;bldg_cf:*\" \"\" \"singlehex/light_1.gif\"");
            p.println("super * \"building:1;bldg_elev:3;bldg_cf:*\" \"\" \"singlehex/light_4.gif\"");
            p.println("super * \"building:1;bldg_elev:4;bldg_cf:*\" \"\" \"singlehex/light_2.gif\"");
            p.println("");
            p.println("super * \"building:1:00;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof00.gif\"");
            p.println("super * \"building:1:01;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof01.gif\"");
            p.println("super * \"building:1:02;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof02.gif\"");
            p.println("super * \"building:1:03;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof03.gif\"");
            p.println("super * \"building:1:04;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof04.gif\"");
            p.println("super * \"building:1:05;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof05.gif\"");
            p.println("super * \"building:1:06;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof06.gif\"");
            p.println("super * \"building:1:07;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof07.gif\"");
            p.println("super * \"building:1:08;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof08.gif\"");
            p.println("super * \"building:1:09;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof09.gif\"");
            p.println("super * \"building:1:10;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof10.gif\"");
            p.println("super * \"building:1:11;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof11.gif\"");
            p.println("super * \"building:1:12;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof12.gif\"");
            p.println("super * \"building:1:13;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof13.gif\"");
            p.println("super * \"building:1:14;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof14.gif\"");
            p.println("super * \"building:1:15;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof15.gif\"");
            p.println("super * \"building:1:16;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof16.gif\"");
            p.println("super * \"building:1:17;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof17.gif\"");
            p.println("super * \"building:1:18;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof18.gif\"");
            p.println("super * \"building:1:19;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof19.gif\"");
            p.println("super * \"building:1:20;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof20.gif\"");
            p.println("super * \"building:1:21;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof21.gif\"");
            p.println("super * \"building:1:22;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof22.gif\"");
            p.println("super * \"building:1:23;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof23.gif\"");
            p.println("super * \"building:1:24;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof24.gif\"");
            p.println("super * \"building:1:25;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof25.gif\"");
            p.println("super * \"building:1:26;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof26.gif\"");
            p.println("super * \"building:1:27;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof27.gif\"");
            p.println("super * \"building:1:28;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof28.gif\"");
            p.println("super * \"building:1:29;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof29.gif\"");
            p.println("super * \"building:1:30;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof30.gif\"");
            p.println("super * \"building:1:31;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof31.gif\"");
            p.println("super * \"building:1:32;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof32.gif\"");
            p.println("super * \"building:1:33;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof33.gif\"");
            p.println("super * \"building:1:34;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof34.gif\"");
            p.println("super * \"building:1:35;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof35.gif\"");
            p.println("super * \"building:1:36;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof36.gif\"");
            p.println("super * \"building:1:37;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof37.gif\"");
            p.println("super * \"building:1:38;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof38.gif\"");
            p.println("super * \"building:1:39;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof39.gif\"");
            p.println("super * \"building:1:40;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof40.gif\"");
            p.println("super * \"building:1:41;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof41.gif\"");
            p.println("super * \"building:1:42;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof42.gif\"");
            p.println("super * \"building:1:43;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof43.gif\"");
            p.println("super * \"building:1:44;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof44.gif\"");
            p.println("super * \"building:1:45;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof45.gif\"");
            p.println("super * \"building:1:46;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof46.gif\"");
            p.println("super * \"building:1:47;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof47.gif\"");
            p.println("super * \"building:1:48;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof48.gif\"");
            p.println("super * \"building:1:49;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof49.gif\"");
            p.println("super * \"building:1:50;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof50.gif\"");
            p.println("super * \"building:1:51;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof51.gif\"");
            p.println("super * \"building:1:52;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof52.gif\"");
            p.println("super * \"building:1:53;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof53.gif\"");
            p.println("super * \"building:1:54;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof54.gif\"");
            p.println("super * \"building:1:55;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof55.gif\"");
            p.println("super * \"building:1:56;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof56.gif\"");
            p.println("super * \"building:1:57;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof57.gif\"");
            p.println("super * \"building:1:58;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof58.gif\"");
            p.println("super * \"building:1:59;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof59.gif\"");
            p.println("super * \"building:1:60;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof60.gif\"");
            p.println("super * \"building:1:61;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof61.gif\"");
            p.println("super * \"building:1:62;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof62.gif\"");
            p.println("super * \"building:1:63;bldg_elev:*;bldg_cf:*\" \"\" \"light/light_roof63.gif\"");
            p.println("");
            p.println("super * \"building:2:00;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof00.gif\"");
            p.println("super * \"building:2:01;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof01.gif\"");
            p.println("super * \"building:2:02;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof02.gif\"");
            p.println("super * \"building:2:03;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof03.gif\"");
            p.println("super * \"building:2:04;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof04.gif\"");
            p.println("super * \"building:2:05;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof05.gif\"");
            p.println("super * \"building:2:06;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof06.gif\"");
            p.println("super * \"building:2:07;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof07.gif\"");
            p.println("super * \"building:2:08;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof08.gif\"");
            p.println("super * \"building:2:09;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof09.gif\"");
            p.println("super * \"building:2:10;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof10.gif\"");
            p.println("super * \"building:2:11;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof11.gif\"");
            p.println("super * \"building:2:12;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof12.gif\"");
            p.println("super * \"building:2:13;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof13.gif\"");
            p.println("super * \"building:2:14;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof14.gif\"");
            p.println("super * \"building:2:15;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof15.gif\"");
            p.println("super * \"building:2:16;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof16.gif\"");
            p.println("super * \"building:2:17;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof17.gif\"");
            p.println("super * \"building:2:18;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof18.gif\"");
            p.println("super * \"building:2:19;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof19.gif\"");
            p.println("super * \"building:2:20;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof20.gif\"");
            p.println("super * \"building:2:21;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof21.gif\"");
            p.println("super * \"building:2:22;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof22.gif\"");
            p.println("super * \"building:2:23;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof23.gif\"");
            p.println("super * \"building:2:24;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof24.gif\"");
            p.println("super * \"building:2:25;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof25.gif\"");
            p.println("super * \"building:2:26;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof26.gif\"");
            p.println("super * \"building:2:27;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof27.gif\"");
            p.println("super * \"building:2:28;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof28.gif\"");
            p.println("super * \"building:2:29;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof29.gif\"");
            p.println("super * \"building:2:30;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof30.gif\"");
            p.println("super * \"building:2:31;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof31.gif\"");
            p.println("super * \"building:2:32;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof32.gif\"");
            p.println("super * \"building:2:33;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof33.gif\"");
            p.println("super * \"building:2:34;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof34.gif\"");
            p.println("super * \"building:2:35;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof35.gif\"");
            p.println("super * \"building:2:36;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof36.gif\"");
            p.println("super * \"building:2:37;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof37.gif\"");
            p.println("super * \"building:2:38;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof38.gif\"");
            p.println("super * \"building:2:39;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof39.gif\"");
            p.println("super * \"building:2:40;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof40.gif\"");
            p.println("super * \"building:2:41;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof41.gif\"");
            p.println("super * \"building:2:42;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof42.gif\"");
            p.println("super * \"building:2:43;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof43.gif\"");
            p.println("super * \"building:2:44;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof44.gif\"");
            p.println("super * \"building:2:45;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof45.gif\"");
            p.println("super * \"building:2:46;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof46.gif\"");
            p.println("super * \"building:2:47;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof47.gif\"");
            p.println("super * \"building:2:48;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof48.gif\"");
            p.println("super * \"building:2:49;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof49.gif\"");
            p.println("super * \"building:2:50;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof50.gif\"");
            p.println("super * \"building:2:51;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof51.gif\"");
            p.println("super * \"building:2:52;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof52.gif\"");
            p.println("super * \"building:2:53;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof53.gif\"");
            p.println("super * \"building:2:54;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof54.gif\"");
            p.println("super * \"building:2:55;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof55.gif\"");
            p.println("super * \"building:2:56;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof56.gif\"");
            p.println("super * \"building:2:57;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof57.gif\"");
            p.println("super * \"building:2:58;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof58.gif\"");
            p.println("super * \"building:2:59;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof59.gif\"");
            p.println("super * \"building:2:60;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof60.gif\"");
            p.println("super * \"building:2:61;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof61.gif\"");
            p.println("super * \"building:2:62;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof62.gif\"");
            p.println("super * \"building:2:63;bldg_elev:*;bldg_cf:*\" \"\" \"megaart/roof63.gif\"");
            p.println("");
            p.println("super * \"building:3:00;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof00.gif\"");
            p.println("super * \"building:3:01;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof01.gif\"");
            p.println("super * \"building:3:02;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof02.gif\"");
            p.println("super * \"building:3:03;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof03.gif\"");
            p.println("super * \"building:3:04;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof04.gif\"");
            p.println("super * \"building:3:05;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof05.gif\"");
            p.println("super * \"building:3:06;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof06.gif\"");
            p.println("super * \"building:3:07;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof07.gif\"");
            p.println("super * \"building:3:08;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof08.gif\"");
            p.println("super * \"building:3:09;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof09.gif\"");
            p.println("super * \"building:3:10;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof10.gif\"");
            p.println("super * \"building:3:11;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof11.gif\"");
            p.println("super * \"building:3:12;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof12.gif\"");
            p.println("super * \"building:3:13;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof13.gif\"");
            p.println("super * \"building:3:14;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof14.gif\"");
            p.println("super * \"building:3:15;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof15.gif\"");
            p.println("super * \"building:3:16;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof16.gif\"");
            p.println("super * \"building:3:17;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof17.gif\"");
            p.println("super * \"building:3:18;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof18.gif\"");
            p.println("super * \"building:3:19;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof19.gif\"");
            p.println("super * \"building:3:20;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof20.gif\"");
            p.println("super * \"building:3:21;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof21.gif\"");
            p.println("super * \"building:3:22;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof22.gif\"");
            p.println("super * \"building:3:23;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof23.gif\"");
            p.println("super * \"building:3:24;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof24.gif\"");
            p.println("super * \"building:3:25;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof25.gif\"");
            p.println("super * \"building:3:26;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof26.gif\"");
            p.println("super * \"building:3:27;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof27.gif\"");
            p.println("super * \"building:3:28;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof28.gif\"");
            p.println("super * \"building:3:29;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof29.gif\"");
            p.println("super * \"building:3:30;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof30.gif\"");
            p.println("super * \"building:3:31;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof31.gif\"");
            p.println("super * \"building:3:32;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof32.gif\"");
            p.println("super * \"building:3:33;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof33.gif\"");
            p.println("super * \"building:3:34;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof34.gif\"");
            p.println("super * \"building:3:35;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof35.gif\"");
            p.println("super * \"building:3:36;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof36.gif\"");
            p.println("super * \"building:3:37;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof37.gif\"");
            p.println("super * \"building:3:38;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof38.gif\"");
            p.println("super * \"building:3:39;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof39.gif\"");
            p.println("super * \"building:3:40;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof40.gif\"");
            p.println("super * \"building:3:41;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof41.gif\"");
            p.println("super * \"building:3:42;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof42.gif\"");
            p.println("super * \"building:3:43;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof43.gif\"");
            p.println("super * \"building:3:44;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof44.gif\"");
            p.println("super * \"building:3:45;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof45.gif\"");
            p.println("super * \"building:3:46;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof46.gif\"");
            p.println("super * \"building:3:47;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof47.gif\"");
            p.println("super * \"building:3:48;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof48.gif\"");
            p.println("super * \"building:3:49;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof49.gif\"");
            p.println("super * \"building:3:50;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof50.gif\"");
            p.println("super * \"building:3:51;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof51.gif\"");
            p.println("super * \"building:3:52;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof52.gif\"");
            p.println("super * \"building:3:53;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof53.gif\"");
            p.println("super * \"building:3:54;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof54.gif\"");
            p.println("super * \"building:3:55;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof55.gif\"");
            p.println("super * \"building:3:56;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof56.gif\"");
            p.println("super * \"building:3:57;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof57.gif\"");
            p.println("super * \"building:3:58;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof58.gif\"");
            p.println("super * \"building:3:59;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof59.gif\"");
            p.println("super * \"building:3:60;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof60.gif\"");
            p.println("super * \"building:3:61;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof61.gif\"");
            p.println("super * \"building:3:62;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof62.gif\"");
            p.println("super * \"building:3:63;bldg_elev:*;bldg_cf:*\" \"\" \"heavy/heavy_roof63.gif\"");
            p.println("");
            p.println("super * \"building:4:00;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof00.gif\"");
            p.println("super * \"building:4:01;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof01.gif\"");
            p.println("super * \"building:4:02;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof02.gif\"");
            p.println("super * \"building:4:03;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof03.gif\"");
            p.println("super * \"building:4:04;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof04.gif\"");
            p.println("super * \"building:4:05;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof05.gif\"");
            p.println("super * \"building:4:06;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof06.gif\"");
            p.println("super * \"building:4:07;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof07.gif\"");
            p.println("super * \"building:4:08;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof08.gif\"");
            p.println("super * \"building:4:09;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof09.gif\"");
            p.println("super * \"building:4:10;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof10.gif\"");
            p.println("super * \"building:4:11;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof11.gif\"");
            p.println("super * \"building:4:12;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof12.gif\"");
            p.println("super * \"building:4:13;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof13.gif\"");
            p.println("super * \"building:4:14;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof14.gif\"");
            p.println("super * \"building:4:15;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof15.gif\"");
            p.println("super * \"building:4:16;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof16.gif\"");
            p.println("super * \"building:4:17;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof17.gif\"");
            p.println("super * \"building:4:18;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof18.gif\"");
            p.println("super * \"building:4:19;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof19.gif\"");
            p.println("super * \"building:4:20;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof20.gif\"");
            p.println("super * \"building:4:21;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof21.gif\"");
            p.println("super * \"building:4:22;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof22.gif\"");
            p.println("super * \"building:4:23;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof23.gif\"");
            p.println("super * \"building:4:24;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof24.gif\"");
            p.println("super * \"building:4:25;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof25.gif\"");
            p.println("super * \"building:4:26;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof26.gif\"");
            p.println("super * \"building:4:27;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof27.gif\"");
            p.println("super * \"building:4:28;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof28.gif\"");
            p.println("super * \"building:4:29;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof29.gif\"");
            p.println("super * \"building:4:30;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof30.gif\"");
            p.println("super * \"building:4:31;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof31.gif\"");
            p.println("super * \"building:4:32;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof32.gif\"");
            p.println("super * \"building:4:33;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof33.gif\"");
            p.println("super * \"building:4:34;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof34.gif\"");
            p.println("super * \"building:4:35;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof35.gif\"");
            p.println("super * \"building:4:36;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof36.gif\"");
            p.println("super * \"building:4:37;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof37.gif\"");
            p.println("super * \"building:4:38;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof38.gif\"");
            p.println("super * \"building:4:39;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof39.gif\"");
            p.println("super * \"building:4:40;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof40.gif\"");
            p.println("super * \"building:4:41;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof41.gif\"");
            p.println("super * \"building:4:42;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof42.gif\"");
            p.println("super * \"building:4:43;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof43.gif\"");
            p.println("super * \"building:4:44;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof44.gif\"");
            p.println("super * \"building:4:45;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof45.gif\"");
            p.println("super * \"building:4:46;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof46.gif\"");
            p.println("super * \"building:4:47;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof47.gif\"");
            p.println("super * \"building:4:48;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof48.gif\"");
            p.println("super * \"building:4:49;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof49.gif\"");
            p.println("super * \"building:4:50;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof50.gif\"");
            p.println("super * \"building:4:51;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof51.gif\"");
            p.println("super * \"building:4:52;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof52.gif\"");
            p.println("super * \"building:4:53;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof53.gif\"");
            p.println("super * \"building:4:54;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof54.gif\"");
            p.println("super * \"building:4:55;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof55.gif\"");
            p.println("super * \"building:4:56;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof56.gif\"");
            p.println("super * \"building:4:57;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof57.gif\"");
            p.println("super * \"building:4:58;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof58.gif\"");
            p.println("super * \"building:4:59;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof59.gif\"");
            p.println("super * \"building:4:60;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof60.gif\"");
            p.println("super * \"building:4:61;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof61.gif\"");
            p.println("super * \"building:4:62;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof62.gif\"");
            p.println("super * \"building:4:63;bldg_elev:*;bldg_cf:*\" \"\" \"hardened/hardened_roof63.gif\"");
            p.println("");
            p.println("super * \"rubble:1\" \"\" \"boring/rubble_light.gif\"");
            p.println("super * \"rubble:2\" \"\" \"boring/rubble_medium.gif\"");
            p.println("super * \"rubble:3\" \"\" \"boring/rubble_heavy.gif\"");
            p.println("super * \"rubble:4\" \"\" \"boring/rubble_hardened.gif\"");
            p.println("");
            p.println("super * \"fluff:6:00\" \"\" \"fluff/skylight1.gif\"");
            p.println("super * \"fluff:6:01\" \"\" \"fluff/skylight2.gif\"");
            p.println("super * \"fluff:6:02\" \"\" \"fluff/skylight3.gif\"");
            p.println("super * \"fluff:6:03\" \"\" \"fluff/skylight4.gif\"");
            p.println("super * \"fluff:6:04\" \"\" \"fluff/skylight5.gif\"");
            p.println("super * \"fluff:6:05\" \"\" \"fluff/skylight6.gif\"");
            p.println("super * \"fluff:6:06\" \"\" \"fluff/stack1.gif\"");
            p.println("super * \"fluff:6:07\" \"\" \"fluff/stack2.gif\"");
            p.println("super * \"fluff:6:08\" \"\" \"fluff/stack3.gif\"");
            p.println("super * \"fluff:6:09\" \"\" \"fluff/stack4.gif\"");
            p.println("super * \"fluff:6:10\" \"\" \"fluff/stack5.gif\"");
            p.println("super * \"fluff:6:11\" \"\" \"fluff/stack6.gif\"");
            p.println("super * \"fluff:6:12\" \"\" \"fluff/bevel1.gif\"");
            p.println("super * \"fluff:6:13\" \"\" \"fluff/bevel2.gif\"");
            p.println("super * \"fluff:6:14\" \"\" \"fluff/bevel3.gif\"");
            p.println("super * \"fluff:6:15\" \"\" \"fluff/bevel4.gif\"");
            p.println("super * \"fluff:6:16\" \"\" \"fluff/bevel5.gif\"");
            p.println("super * \"fluff:6:17\" \"\" \"fluff/bevel6.gif\"");
            p.println("super * \"fluff:6:18\" \"\" \"fluff/pool1.gif\"");
            p.println("");
            p.println("super * \"fluff:8:00\" \"\" \"fluff/ledge1.gif\"");
            p.println("super * \"fluff:8:01\" \"\" \"fluff/ledge2.gif\"");
            p.println("super * \"fluff:8:02\" \"\" \"fluff/ledge3.gif\"");
            p.println("super * \"fluff:8:03\" \"\" \"fluff/ledge4.gif\"");
            p.println("super * \"fluff:8:04\" \"\" \"fluff/ledge5.gif\"");
            p.println("super * \"fluff:8:05\" \"\" \"fluff/ledge6.gif\"");
            p.println("");
            p.println("super * \"fluff:2:00\" \"\" \"fluff/heli1.gif\"");
            p.println("super * \"fluff:2:01\" \"\" \"fluff/heli2.gif\"");
            p.println("super * \"fluff:2:02\" \"\" \"fluff/heli3.gif\"");
            p.println("super * \"fluff:2:03\" \"\" \"fluff/beacon1.gif\"");
            p.println("super * \"fluff:2:04\" \"\" \"fluff/beacon2.gif\"");
            p.println("");
            p.println("super * \"smoke:1\" \"\" \"boring/smoke.gif\"");
            p.println("super * \"smoke:2\" \"\" \"boring/heavysmoke.gif\"");
            p.println("super * \"smoke:3\" \"\" \"boring/lismoke.gif\"");
            p.println("super * \"smoke:4\" \"\" \"boring/lismoke.gif\"");
            p.println("super * \"fire:1\" \"\" \"boring/fire.gif\"");
            p.println("super * \"fire:2\" \"\" \"boring/fire.gif\"");
            p.println("");
            p.println("super * \"fortified:1\" \"\" \"boring/sandbags.gif\"");
            p.println("");
            p.println("super * \"arms:1\" \"\" \"limbs/arm1.gif\"");
            p.println("super * \"arms:2\" \"\" \"limbs/arm2.gif\"");
            p.println("super * \"arms:3\" \"\" \"limbs/arm2.gif\"");
            p.println("super * \"legs:1\" \"\" \"limbs/leg1.gif\"");
            p.println("super * \"legs:2\" \"\" \"limbs/leg2.gif\"");
            p.println("super * \"legs:3\" \"\" \"limbs/leg2.gif\"");
            p.println("");
            p.println("super * \"woods:1\" \"\" \"boring/lf0.gif;boring/lf1.gif;boring/lf2.gif;boring/lf3.gif;boring/lf4.gif\"");
            p.println("super * \"woods:2\" \"\" \"boring/hf0.gif;boring/hf1.gif;boring/hf2.gif;boring/hf3.gif\"");
            p.println("super * \"woods:3\" \"\" \"boring/uhf.gif\"");

            p.println("super * \"jungle:1\" \"\" \"jungle/light_jungle1.png;jungle/light_jungle2.png;jungle/light_jungle3.png;jungle/light_jungle4.png\"");
            p.println("super * \"jungle:2\" \"\" \"jungle/heavy_jungle1.png;jungle/heavy_jungle2.png;jungle/heavy_jungle3.png\"");
            p.println("super * \"jungle:3\" \"\" \"jungle/ultra_heavy_jungle1.png\"");
            p.println("");
            p.println("base 0 \"\" \"\" \"boring/beige_plains_0.gif\"");
            p.println("base 1 \"\" \"\" \"boring/beige_plains_1.gif\"");
            p.println("base 2 \"\" \"\" \"boring/beige_plains_2.gif\"");
            p.println("base 3 \"\" \"\" \"boring/beige_plains_3.gif\"");
            p.println("base 4 \"\" \"\" \"boring/beige_plains_4.gif\"");
            p.println("base 5 \"\" \"\" \"boring/beige_plains_5.gif\"");
            p.println("base 6 \"\" \"\" \"boring/beige_plains_6.gif\"");
            p.println("base 7 \"\" \"\" \"boring/beige_plains_7.gif\"");
            p.println("base 8 \"\" \"\" \"boring/beige_plains_8.gif\"");
            p.println("base 9 \"\" \"\" \"boring/beige_plains_9.gif\"");
            p.println("base 10 \"\" \"\" \"boring/beige_plains_10.gif\"");
            p.println("base -1 \"\" \"\" \"boring/beige_sinkhole_1.gif\"");
            p.println("base -2 \"\" \"\" \"boring/beige_sinkhole_2.gif\"");
            p.println("base -3 \"\" \"\" \"boring/beige_sinkhole_3.gif\"");
            p.println("");
            p.println("base * \"impassable:1\" \"\" \"boring/solidrock.gif\"");
            p.println("");
            p.println("super * \"rough:1\" \"\" \"boring/beige_rough_0.gif\"");
            p.println("");
            p.println("base 0 \"ice:1\" \"\" \"boring/ice_0.gif\"");
            p.println("base 1 \"ice:1\" \"\" \"boring/ice_1.gif\"");
            p.println("base 2 \"ice:1\" \"\" \"boring/ice_2.gif\"");
            p.println("base 3 \"ice:1\" \"\" \"boring/ice_3.gif\"");
            p.println("base 4 \"ice:1\" \"\" \"boring/ice_4.gif\"");
            p.println("base 5 \"ice:1\" \"\" \"boring/ice_5.gif\"");
            p.println("base 6 \"ice:1\" \"\" \"boring/ice_6.gif\"");
            p.println("base 7 \"ice:1\" \"\" \"boring/ice_7.gif\"");
            p.println("base 8 \"ice:1\" \"\" \"boring/ice_8.gif\"");
            p.println("base 9 \"ice:1\" \"\" \"boring/ice_9.gif\"");
            p.println("base 10 \"ice:1\" \"\" \"boring/ice_10.gif\"");
            p.println("base -1 \"ice:1\" \"\" \"boring/ice_-1.gif\"");
            p.println("base -2 \"ice:1\" \"\" \"boring/ice_-2.gif\"");
            p.println("base -3 \"ice:1\" \"\" \"boring/ice_-3.gif\"");
            p.println("base -4 \"ice:1\" \"\" \"boring/ice_-4.gif\"");
            p.println("base -5 \"ice:1\" \"\" \"boring/ice_-5.gif\"");
            p.println("base -6 \"ice:1\" \"\" \"boring/ice_-6.gif\"");
            p.println("");
            p.println("base * \"water:1\" \"\" \"boring/blue_water_1.gif\"");
            p.println("base * \"water:2\" \"\" \"boring/blue_water_2.gif\"");
            p.println("base * \"water:3\" \"\" \"boring/blue_water_3.gif\"");
            p.println("base * \"water:4\" \"\" \"boring/blue_water_4.gif\"");
            p.println("");
            p.println("base 0 \"pavement:1\" \"\" \"boring/grey_pavement_0.gif\"");
            p.println("base 1 \"pavement:1\" \"\" \"boring/grey_pavement_1.gif\"");
            p.println("base 2 \"pavement:1\" \"\" \"boring/grey_pavement_2.gif\"");
            p.println("base 3 \"pavement:1\" \"\" \"boring/grey_pavement_2.gif\"");
            p.println("base 4 \"pavement:1\" \"\" \"boring/grey_pavement_3.gif\"");
            p.println("base 5 \"pavement:1\" \"\" \"boring/grey_pavement_3.gif\"");
            p.println("base 6 \"pavement:1\" \"\" \"boring/grey_pavement_4.gif\"");
            p.println("base 7 \"pavement:1\" \"\" \"boring/grey_pavement_4.gif\"");
            p.println("base 8 \"pavement:1\" \"\" \"boring/grey_pavement_4.gif\"");
            p.println("base 9 \"pavement:1\" \"\" \"boring/grey_pavement_4.gif\"");
            p.println("base 10 \"pavement:1\" \"\" \"boring/grey_pavement_5.gif\"");
            p.println("base 20 \"pavement:1\" \"\" \"boring/grey_pavement_6.gif\"");
            p.println("base 30 \"pavement:1\" \"\" \"boring/grey_pavement_7.gif\"");
            p.println("base 40 \"pavement:1\" \"\" \"boring/grey_pavement_8.gif\"");
            p.println("");
            p.println("super * \"swamp:1\" \"\" \"swamp/swamp_0.png;swamp/swamp_1.png;swamp/swamp_2.png;swamp/swamp_3.png\"");
            p.println("base 0 \"rough:1;swamp:1\" \"\" \"swamp/swamp_rough_0.gif\"");
            p.println("base 0 \"rough:1;swamp:2\" \"\" \"swamp/swamp_rough_0a.gif\"");
            p.println("base 0 \"rough:1;swamp:3\" \"\" \"swamp/swamp_rough_0b.gif\"");
            p.println("base 0 \"rough:1;swamp:4\" \"\" \"swamp/swamp_rough_0c.gif\"");
            p.println("base 1 \"rough:1;swamp:1\" \"\" \"swamp/swamp_rough_1.gif\"");
            p.println("base 2 \"rough:1;swamp:1\" \"\" \"swamp/swamp_rough_2.gif\"");
            p.println("base 3 \"rough:1;swamp:1\" \"\" \"swamp/swamp_rough_3.gif\"");
            p.println("base 4 \"rough:1;swamp:1\" \"\" \"swamp/swamp_rough_4.gif\"");
            p.println("base 5 \"rough:1;swamp:1\" \"\" \"swamp/swamp_rough_5.gif\"");
            p.println("base 6 \"rough:1;swamp:1\" \"\" \"swamp/swamp_rough_6.gif\"");
            p.println("base 7 \"rough:1;swamp:1\" \"\" \"swamp/swamp_rough_7.gif\"");
            p.println("base 8 \"rough:1;swamp:1\" \"\" \"swamp/swamp_rough_8.gif\"");
            p.println("base 9 \"rough:1;swamp:1\" \"\" \"swamp/swamp_rough_9.gif\"");
            p.println("base -1 \"rough:1;swamp:1\" \"\" \"swamp/swamp_rough_-1.gif\"");
            p.println("base -2 \"rough:1;swamp:1\" \"\" \"swamp/swamp_rough_-2.gif\"");
            p.println("base -3 \"rough:1;swamp:1\" \"\" \"swamp/swamp_rough_-3.gif\"");
            p.println("base 0 \"woods:1;swamp:1\" \"\" \"swamp/swamp_light_forest_0.gif\"");
            p.println("base 1 \"woods:1;swamp:1\" \"\" \"swamp/swamp_light_forest_1.gif\"");
            p.println("base 2 \"woods:1;swamp:1\" \"\" \"swamp/swamp_light_forest_2.gif\"");
            p.println("base 0 \"woods:2;swamp:1\" \"\" \"swamp/swamp_heavy_forest_0.gif\"");
            p.println("base 1 \"woods:2;swamp:1\" \"\" \"swamp/swamp_heavy_forest_1.gif\"");
            p.println("");
            p.println("base -2 \"magma:1\" \"\" \"magma/crust_-2.gif\"");
            p.println("base -1 \"magma:1\" \"\" \"magma/crust_-1.gif\"");
            p.println("base 0 \"magma:1\" \"\" \"magma/crust_0.gif\"");
            p.println("base 1 \"magma:1\" \"\" \"magma/crust_1.gif\"");
            p.println("base 2 \"magma:1\" \"\" \"magma/crust_2.gif\"");
            p.println("base 3 \"magma:1\" \"\" \"magma/crust_3.gif\"");
            p.println("");
            p.println("base -2 \"magma:2\" \"\" \"magma/magma_-2.gif\"");
            p.println("base -1 \"magma:2\" \"\" \"magma/magma_-1.gif\"");
            p.println("base 0 \"magma:2\" \"\" \"magma/magma_0.gif\"");
            p.println("base 1 \"magma:2\" \"\" \"magma/magma_1.gif\"");
            p.println("base 2 \"magma:2\" \"\" \"magma/magma_2.gif\"");
            p.println("base 3 \"magma:2\" \"\" \"magma/magma_3.gif\"");
            p.println("");
            p.println("base -2 \"mud:1\" \"\" \"mud/mud_-2.gif\"");
            p.println("base -1 \"mud:1\" \"\" \"mud/mud_-1.gif\"");
            p.println("base 0 \"mud:1\" \"\" \"mud/mud_0.gif\"");
            p.println("base 1 \"mud:1\" \"\" \"mud/mud_1.gif\"");
            p.println("base 2 \"mud:1\" \"\" \"mud/mud_2.gif\"");
            p.println("base 3 \"mud:1\" \"\" \"mud/mud_3.gif\"");
            p.println("");
            p.println("base -2 \"mud:2\" \"\" \"mud/deepmud_-2.gif\"");
            p.println("base -1 \"mud:2\" \"\" \"mud/deepmud_-1.gif\"");
            p.println("base 0 \"mud:2\" \"\" \"mud/deepmud_0.gif\"");
            p.println("base 1 \"mud:2\" \"\" \"mud/deepmud_1.gif\"");
            p.println("base 2 \"mud:2\" \"\" \"mud/deepmud_2.gif\"");
            p.println("base 3 \"mud:2\" \"\" \"mud/deepmud_3.gif\"");
            p.println("");
            p.println("base -2 \"sand:1\" \"\" \"sand/sand_-2.gif\"");
            p.println("base -1 \"sand:1\" \"\" \"sand/sand_-1.gif\"");
            p.println("base 0 \"sand:1\" \"\" \"sand/sand_0.gif\"");
            p.println("base 1 \"sand:1\" \"\" \"sand/sand_1.gif\"");
            p.println("base 2 \"sand:1\" \"\" \"sand/sand_2.gif\"");
            p.println("base 3 \"sand:1\" \"\" \"sand/sand_3.gif\"");
            p.println("");
            p.println("base -2 \"tundra:1\" \"\" \"tundra/tundra_-2.gif\"");
            p.println("base -1 \"tundra:1\" \"\" \"tundra/tundra_-1.gif\"");
            p.println("base 0 \"tundra:1\" \"\" \"tundra/tundra_0.gif\"");
            p.println("base 1 \"tundra:1\" \"\" \"tundra/tundra_1.gif\"");
            p.println("base 2 \"tundra:1\" \"\" \"tundra/tundra_2.gif\"");
            p.println("base 3 \"tundra:1\" \"\" \"tundra/tundra_3.gif\"");
            p.println("");
            p.println("#------------------- BEGIN snow theme");
            p.println("");
            p.println("base 0 \"\" \"snow\" \"snow/snow_0.gif\"");
            p.println("base 1 \"\" \"snow\" \"snow/snow_1.gif\"");
            p.println("base 2 \"\" \"snow\" \"snow/snow_2.gif\"");
            p.println("base 3 \"\" \"snow\" \"snow/snow_3.gif\"");
            p.println("base 4 \"\" \"snow\" \"snow/snow_4.gif\"");
            p.println("base 5 \"\" \"snow\" \"snow/snow_5.gif\"");
            p.println("base 6 \"\" \"snow\" \"snow/snow_6.gif\"");
            p.println("base 7 \"\" \"snow\" \"snow/snow_7.gif\"");
            p.println("base 8 \"\" \"snow\" \"snow/snow_8.gif\"");
            p.println("base 9 \"\" \"snow\" \"snow/snow_9.gif\"");
            p.println("base 10 \"\" \"snow\" \"snow/snow_10.gif\"");
            p.println("base -1 \"\" \"snow\" \"snow/snow_-1.gif\"");
            p.println("base -2 \"\" \"snow\" \"snow/snow_-2.gif\"");
            p.println("base -3 \"\" \"snow\" \"snow/snow_-3.gif\"");
            p.println("base -4 \"\" \"snow\" \"snow/snow_-4.gif\"");
            p.println("base -5 \"\" \"snow\" \"snow/snow_-5.gif\"");
            p.println("base -6 \"\" \"snow\" \"snow/snow_-6.gif\"");
            p.println("");
            p.println("base 0 \"pavement:1\" \"snow\" \"boring/ice_0.gif\"");
            p.println("base 1 \"pavement:1\" \"snow\" \"boring/ice_1.gif\"");
            p.println("base 2 \"pavement:1\" \"snow\" \"boring/ice_2.gif\"");
            p.println("base 3 \"pavement:1\" \"snow\" \"boring/ice_3.gif\"");
            p.println("base 4 \"pavement:1\" \"snow\" \"boring/ice_4.gif\"");
            p.println("base 5 \"pavement:1\" \"snow\" \"boring/ice_5.gif\"");
            p.println("base 6 \"pavement:1\" \"snow\" \"boring/ice_6.gif\"");
            p.println("base 7 \"pavement:1\" \"snow\" \"boring/ice_7.gif\"");
            p.println("base 8 \"pavement:1\" \"snow\" \"boring/ice_8.gif\"");
            p.println("base 9 \"pavement:1\" \"snow\" \"boring/ice_9.gif\"");
            p.println("base 10 \"pavement:1\" \"snow\" \"boring/ice_10.gif\"");
            p.println("base -1 \"pavement:1\" \"snow\" \"boring/ice_-1.gif\"");
            p.println("base -2 \"pavement:1\" \"snow\" \"boring/ice_-2.gif\"");
            p.println("base -3 \"pavement:1\" \"snow\" \"boring/ice_-3.gif\"");
            p.println("base -4 \"pavement:1\" \"snow\" \"boring/ice_-4.gif\"");
            p.println("base -5 \"pavement:1\" \"snow\" \"boring/ice_-5.gif\"");
            p.println("base -6 \"pavement:1\" \"snow\" \"boring/ice_-6.gif\"");
            p.println("");
            p.println("base 0 \"rough:1\" \"snow\" \"snow/snow_rough_0.gif\"");
            p.println("base 1 \"rough:1\" \"snow\" \"snow/snow_rough_1.gif\"");
            p.println("base 3 \"rough:1\" \"snow\" \"snow/snow_rough_3.gif\"");
            p.println("base 5 \"rough:1\" \"snow\" \"snow/snow_rough_5.gif\"");
            p.println("base -1 \"rough:1\" \"snow\" \"snow/snow_rough_-1.gif\"");
            p.println("base -3 \"rough:1\" \"snow\" \"snow/snow_rough_-3.gif\"");
            p.println("base -5 \"rough:1\" \"snow\" \"snow/snow_rough_-5.gif\"");
            p.println("");
            p.println("base 0 \"woods:1\" \"snow\" \"snow/snow_light_forest_0.gif\"");
            p.println("base 1 \"woods:1\" \"snow\" \"snow/snow_light_forest_1.gif\"");
            p.println("base 2 \"woods:1\" \"snow\" \"snow/snow_light_forest_2.gif\"");
            p.println("base 0 \"woods:2\" \"snow\" \"snow/snow_heavy_forest_0.gif\"");
            p.println("base 1 \"woods:2\" \"snow\" \"snow/snow_heavy_forest_1.gif\"");
            p.println("");
            p.println("#------------------- END snow theme");
            p.println("");
            p.println("#------------------- BEGIN grass theme");
            p.println("");
            p.println("base 0 \"\" \"grass\" \"grass/grass_plains_0.gif\"");
            p.println("base 1 \"\" \"grass\" \"grass/grass_plains_1.gif\"");
            p.println("base 2 \"\" \"grass\" \"grass/grass_plains_2.gif\"");
            p.println("base 3 \"\" \"grass\" \"grass/grass_plains_3.gif\"");
            p.println("base 4 \"\" \"grass\" \"grass/grass_plains_4.gif\"");
            p.println("base 5 \"\" \"grass\" \"grass/grass_plains_5.gif\"");
            p.println("base 6 \"\" \"grass\" \"grass/grass_plains_6.gif\"");
            p.println("base 7 \"\" \"grass\" \"grass/grass_plains_7.gif\"");
            p.println("base 8 \"\" \"grass\" \"grass/grass_plains_8.gif\"");
            p.println("base 9 \"\" \"grass\" \"grass/grass_plains_9.gif\"");
            p.println("base 10 \"\" \"grass\" \"grass/grass_plains_10.gif\"");
            p.println("base -1 \"\" \"grass\" \"grass/grass_sinkhole_1.gif\"");
            p.println("base -2 \"\" \"grass\" \"grass/grass_sinkhole_2.gif\"");
            p.println("base -3 \"\" \"grass\" \"grass/grass_sinkhole_3.gif\"");
            p.println("");
            p.println("base 0 \"swamp:1\" \"grass\" \"grass/grass_swamp_0.gif\"");
            p.println("base 0 \"swamp:2\" \"grass\" \"grass/grass_swamp_0.gif\"");
            p.println("base 0 \"swamp:3\" \"grass\" \"grass/grass_swamp_0.gif\"");
            p.println("base 0 \"swamp:4\" \"grass\" \"grass/grass_swamp_0.gif\"");
            p.println("base 1 \"swamp:1\" \"grass\" \"grass/grass_swamp_1.gif\"");
            p.println("base 2 \"swamp:1\" \"grass\" \"grass/grass_swamp_2.gif\"");
            p.println("base 3 \"swamp:1\" \"grass\" \"grass/grass_swamp_3.gif\"");
            p.println("base 4 \"swamp:1\" \"grass\" \"grass/grass_swamp_4.gif\"");
            p.println("base 5 \"swamp:1\" \"grass\" \"grass/grass_swamp_5.gif\"");
            p.println("base 6 \"swamp:1\" \"grass\" \"grass/grass_swamp_6.gif\"");
            p.println("base 7 \"swamp:1\" \"grass\" \"grass/grass_swamp_7.gif\"");
            p.println("base 8 \"swamp:1\" \"grass\" \"grass/grass_swamp_8.gif\"");
            p.println("base 9 \"swamp:1\" \"grass\" \"grass/grass_swamp_9.gif\"");
            p.println("base 10 \"swamp:1\" \"grass\" \"grass/grass_swamp_10.gif\"");
            p.println("base -1 \"swamp:1\" \"grass\" \"grass/grass_swamp_-1.gif\"");
            p.println("base -2 \"swamp:1\" \"grass\" \"grass/grass_swamp_-2.gif\"");
            p.println("base -3 \"swamp:1\" \"grass\" \"grass/grass_swamp_-3.gif\"");
            p.println("base 0 \"woods:1;swamp:1\" \"grass\" \"grass/grass_l_swamp_0.gif\"");
            p.println("base 1 \"woods:1;swamp:1\" \"grass\" \"grass/grass_l_swamp_1.gif\"");
            p.println("base 2 \"woods:1;swamp:1\" \"grass\" \"grass/grass_l_swamp_2.gif\"");
            p.println("base 0 \"woods:2;swamp:1\" \"grass\" \"grass/grass_h_swamp_0.gif\"");
            p.println("base 1 \"woods:2;swamp:1\" \"grass\" \"grass/grass_h_swamp_1.gif\"");
            p.println("");
            p.println("#------------------- END grass theme");
            p.println("");
            p.println("#------------------- BEGIN mars theme");
            p.println("");
            p.println("base 0 \"\" \"mars\" \"mars/mars_plains_0.gif\"");
            p.println("base 1 \"\" \"mars\" \"mars/mars_plains_1.gif\"");
            p.println("base 2 \"\" \"mars\" \"mars/mars_plains_2.gif\"");
            p.println("base 3 \"\" \"mars\" \"mars/mars_plains_3.gif\"");
            p.println("base 4 \"\" \"mars\" \"mars/mars_plains_4.gif\"");
            p.println("base 5 \"\" \"mars\" \"mars/mars_plains_5.gif\"");
            p.println("base 6 \"\" \"mars\" \"mars/mars_plains_6.gif\"");
            p.println("base 7 \"\" \"mars\" \"mars/mars_plains_7.gif\"");
            p.println("base 8 \"\" \"mars\" \"mars/mars_plains_8.gif\"");
            p.println("base 9 \"\" \"mars\" \"mars/mars_plains_9.gif\"");
            p.println("base 10 \"\" \"mars\" \"mars/mars_plains_10.gif\"");
            p.println("base -1 \"\" \"mars\" \"mars/mars_sinkhole_1.gif\"");
            p.println("base -2 \"\" \"mars\" \"mars/mars_sinkhole_2.gif\"");
            p.println("base -3 \"\" \"mars\" \"mars/mars_sinkhole_3.gif\"");
            p.println("");
            p.println("#------------------- END mars theme");
            p.println("");
            p.println("#------------------- BEGIN lunar theme");
            p.println("");
            p.println("base 0 \"\" \"lunar\" \"lunar/lunar_plains_0.gif\"");
            p.println("base 1 \"\" \"lunar\" \"lunar/lunar_plains_1.gif\"");
            p.println("base 2 \"\" \"lunar\" \"lunar/lunar_plains_2.gif\"");
            p.println("base 3 \"\" \"lunar\" \"lunar/lunar_plains_3.gif\"");
            p.println("base 4 \"\" \"lunar\" \"lunar/lunar_plains_4.gif\"");
            p.println("base 5 \"\" \"lunar\" \"lunar/lunar_plains_5.gif\"");
            p.println("base 6 \"\" \"lunar\" \"lunar/lunar_plains_6.gif\"");
            p.println("base 7 \"\" \"lunar\" \"lunar/lunar_plains_7.gif\"");
            p.println("base 8 \"\" \"lunar\" \"lunar/lunar_plains_8.gif\"");
            p.println("base 9 \"\" \"lunar\" \"lunar/lunar_plains_9.gif\"");
            p.println("base 10 \"\" \"lunar\" \"lunar/lunar_plains_10.gif\"");
            p.println("base -1 \"\" \"lunar\" \"lunar/lunar_sinkhole_1.gif\"");
            p.println("base -2 \"\" \"lunar\" \"lunar/lunar_sinkhole_2.gif\"");
            p.println("base -3 \"\" \"lunar\" \"lunar/lunar_sinkhole_3.gif\"");
            p.println("");
            p.println("#------------------- END lunar theme");
            p.close();
            fos.close();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

}
