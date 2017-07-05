/*
 * MegaMek - Copyright (C) 2004,2005 Ben Mazur (bmazur@sev.org)
 * 
 *  This program is free software; you can redistribute it and/or modify it 
 *  under the terms of the GNU General Public License as published by the Free 
 *  Software Foundation; either version 2 of the License, or (at your option) 
 *  any later version.
 * 
 *  This program is distributed in the hope that it will be useful, but 
 *  WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY 
 *  or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License 
 *  for more details.
 */
/*
 * Created on Sep 25, 2004
 *
 */
package megamek.common.weapons.capitalweapons;

import megamek.common.TechAdvancement;

/**
 * @author Jay Lawson
 */
public class CLNPPCWeaponHeavy extends NavalPPCWeapon {
    /**
    * 
    */
    private static final long serialVersionUID = 8756042527483383101L;

    /**
    * 
    */
    public CLNPPCWeaponHeavy() {
        super();
        this.name = "Heavy NPPC (Clan)";
        this.setInternalName(this.name);
        this.addLookupName("CLHeavyNPPC");
        this.heat = 225;
        this.damage = 15;
        this.shortRange = 13;
        this.mediumRange = 26;
        this.longRange = 39;
        this.extremeRange = 52;
        this.tonnage = 3000.0f;
        this.bv = 3780;
        this.cost = 9050000;
        this.shortAV = 15;
        this.medAV = 15;
        this.longAV = 15;
        this.extAV = 15;
        this.maxRange = RANGE_EXT;

        techAdvancement.setTechBase(TechAdvancement.TECH_BASE_CLAN);
        techAdvancement.setClanAdvancement(DATE_NONE, 2820, DATE_NONE);
        techAdvancement.setTechRating(RATING_D);
        techAdvancement.setAvailability( new int[] { RATING_D, RATING_D, RATING_E, RATING_X });
    }
}
//Commented out in Weapontype. Tech progression in IS weapon covers these.
