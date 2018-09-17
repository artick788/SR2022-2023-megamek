${fullName}
Type: ${typeDesc}
Mass: ${massDesc} tons
<#if use??>  	
Use: ${use}
</#if>
Technology Base: ${techBase} 
Introduced: ${year}
Mass: ${tonnage}
Battle Value: ${battleValue}
Tech Rating/Availability: ${techRating}
Cost: ${cost} C-bills

<#if dimensions??>
Dimensions
    Length: ${dimensions.length}
    Width: ${dimensions.width}

</#if>	
Fuel: ${fuelMass} tons (${fuelPoints})
Safe Thrust: ${safeThrust}
Maximum Thrust: ${maxThrust}
Heat Sinks: ${hsCount}
Structural Integrity: ${si}

Armor
    Nose: ${armorValues.NOS}
    Sides: ${armorValues.RS}
    Aft: ${armorValues.AFT}

Cargo
<#list bays>
    <#items as bay>
    ${formatBayRow("Bay " + bay?counter + ":", bay.name + " (" + bay.size + ")", bay.doors + (bay.doors == 1)?string(" Door", " Doors"))}
	</#items>
<#else>
    None
</#list>

Ammunition:
<#list ammo as row>
    ${row.shots} rounds of ${row.name} ammunition (${row.tonnage} tons)<#if row?has_next>, </#if>
<#else>
	None
</#list>		

Crew: <#if crew?size gt 0> ${crew?join(", ")}<#else>None</#if>		

Notes: Mounts ${armorMass} tons of ${armorType} armor.

<#if usesWeaponBays>
${formatBayRow("Weapons:", "", "Capital Attack Values (Standard)")}
${formatBayRow("Arc (Heat)", "Heat", "SRV", "MRV", "LRV", "ERV", "Class")}
<#list weaponBayArcs as arc>
${arc} (${weaponBayHeat[arc]} Heat)
<#list weaponBays[arc] as bay>
${formatBayRow(bay.weapons[0], bay.heat, bay.srv, bay.mrv, bay.lrv, bay.erv, bay.class)}
<#list bay.weapons[1..] as wpn>
    ${wpn}
</#list>
</#list>
</#list>
<#else>
Weapons
${formatEquipmentRow("and Ammo", "Location", "Tonnage", "Heat", "SRV", "MRV", "LRV", "ERV")}	
<#list equipment as eq>
${formatEquipmentRow(eq.name, eq.location, eq.tonnage, eq.heat, eq.srv, eq.mrv, eq.lrv, eq.erv)}
</#list>
</#if>
	
<#if quirks??>
Features the following design quirks: ${quirks}
</#if>