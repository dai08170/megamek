/*
 * MegaMek - Copyright (C) 2005 Ben Mazur (bmazur@sev.org)
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
package megamek.client.ratgenerator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

import megamek.common.Compute;
import megamek.common.MechSummary;

/**
 * Manages random assignment table generated by RATGenerator.
 * 
 * @author Neoancient
 *
 */
public class UnitTable {
	
	@FunctionalInterface
	public interface UnitFilter {
		boolean include(MechSummary ms);
	}
	
	private List<TableEntry> table = new ArrayList<TableEntry>();
	private FactionRecord faction;
	private int unitType;
	private int year;
	private String rating;
	private Collection<Integer> weightClasses;
	private int networkMask;
	private Collection<String> subtypes;
	private Collection<MissionRole> roles;
	private int roleStrictness;
	private FactionRecord deployingFaction;
	private HashMap<String,UnitTable> salvageCache =
			new HashMap<String,UnitTable>();

	/* Key is cumulative weight. This is usually more efficient than a weighted list
	 * for large lists. Salvage and units are kept separate so that salvage totals can be
	 * adjusted to maintain the same proportion when the units have a filter applied.
	 */
	private NavigableMap<Integer,FactionRecord> salvageMap;
	private NavigableMap<Integer,MechSummary> unitMap;
	int salvageTotal;
	int unitTotal;
	/* Filtering can reduce the total weight of the units. Calculate the salvage pct when
	 * creating the table to maintain the same proportion. */
	int salvagePct;
	
	public UnitTable(FactionRecord faction, int unitType, int year,
			String rating, Collection<Integer> weightClasses, int networkMask, Collection<String> subtypes,
			Collection<MissionRole> roles, int roleStrictness, FactionRecord deployingFaction) {
		this.faction = faction;
		this.unitType = unitType;
		this.year = year;
		this.rating = rating;
		this.weightClasses = weightClasses;
		this.networkMask = networkMask;
		this.subtypes = subtypes;
		this.roles = roles;
		this.roleStrictness = roleStrictness;
		this.deployingFaction = deployingFaction;
		generateTable();
	}
	
	public UnitTable(FactionRecord faction, int unitType, int year,
			String rating, Collection<Integer> weightClasses, int networkMask,
			Collection<String> subtypes,
			Collection<MissionRole> roles, int roleStrictness) {
		this(faction, unitType, year, rating, weightClasses, networkMask, subtypes,
				roles, roleStrictness, faction);
	}
	
	/**
	 * Generate the RAT, then go through it to build the NavigableMaps that
	 * will be used for random selection.
	 */
	private void generateTable() {
		if (!faction.isActiveInYear(year)) {
			return;
		}
		table = RATGenerator.getInstance().generateTable(faction,
				unitType, year, rating, weightClasses, networkMask, subtypes,
				roles, roleStrictness, deployingFaction);
		Collections.sort(table);
		
		salvageMap = new TreeMap<Integer,FactionRecord>();
		unitMap = new TreeMap<Integer,MechSummary>();
		salvageTotal = 0;
		unitTotal = 0;
		for (TableEntry entry : table) {
			if (entry.isUnit()) {
				unitMap.put(unitTotal, entry.getUnitEntry());
				unitTotal += entry.weight;
			} else {
				salvageMap.put(salvageTotal, entry.getSalvageFaction());
				salvageTotal += entry.weight;
			}
		}
		if (salvageTotal + unitTotal > 0) {
			salvagePct = salvageTotal * 100 / (salvageTotal + unitTotal);
		}
	}
	
	public int getNumEntries() {
		return table.size();
	}
	
	public int getEntryWeight(int index) {
		return table.get(index).weight;
	}
	
	public String getEntryText(int index) {
		if (table.get(index).isUnit()) {
			return table.get(index).getUnitEntry().getName();
		} else {
			if (faction.isClan()) {
				return "Isorla: " + table.get(index).getSalvageFaction().getName(year - 5);
			} else {
				return "Salvage: " + table.get(index).getSalvageFaction().getName(year - 5);
			}
		}
	}
	
	public MechSummary getMechSummary(int index) {
		if (table.get(index).isUnit()) {
			return table.get(index).getUnitEntry();
		}
		return null;
	}
	
	public int getBV(int index) {
		if (table.get(index).isUnit()) {
			return table.get(index).getUnitEntry().getBV();
		} else {
			return 0;
		}
	}
	
	public boolean hasUnits() {
		for (TableEntry entry : table) {
			if (entry.isUnit()) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * 
	 * @return A single MechSummary selected from the full table.
	 */
	public MechSummary generateUnit() {
		return generateUnit(null);
	}
	
	/**
	 * 
	 * @param filter Only units that return true when the filter method is applied will be included
	 *               in the results. If null, all are included.
	 * @return A single MechSummary selected from the filtered table.
	 */
	public MechSummary generateUnit(UnitFilter filter) {
		int roll = Compute.randomInt(100);
		if (roll < salvagePct) {
			MechSummary ms = generateSalvage(filter);
			if (ms != null) {
				return ms;
			}
		}
		NavigableMap<Integer,MechSummary> useUnitMap = unitMap;
		int unitMapSize = unitTotal;
		if (filter != null) {
			useUnitMap = new TreeMap<>();
			unitMapSize = 0;
			for (TableEntry entry : table) {
				if (entry.isUnit() && filter.include(entry.getUnitEntry())) {
					useUnitMap.put(unitMapSize, entry.getUnitEntry());
					unitMapSize += entry.weight;
				}
			}
		}
		
		if (unitMapSize <= 0) {
			return null;
		}
		roll = Compute.randomInt(unitMapSize);
		return useUnitMap.floorEntry(roll).getValue();
	}
	
	/**
	 * 
	 * @param num The number of units to be generated.
	 * @return A list of randomly generated units
	 */
	public ArrayList<MechSummary> generateUnits(int num) {
		return generateUnits(num, null);
	}
	
	/**
	 * 
	 * @param num The number of units to be generated.
	 * @param filter Only units that return true when the filter method is applied will be included
	 *               in the results. If null, all are included.
	 * @return A list of randomly generated units
	 */
	public ArrayList<MechSummary> generateUnits(int num, UnitFilter filter) {
		ArrayList<MechSummary> retVal = new ArrayList<>();
		for (int i = 0; i < num; i++) {
			MechSummary ms = generateUnit(filter);
			if (ms != null) {
				retVal.add(ms);
			}
		}
		return retVal;
	}	
	
	/**
	 * Selects a faction from the salvage list and generates a table using the same parameters
	 * as this table, but from five years earlier. Generated tables are cached for later use.
	 * If the generated table contains no units, it is discarded and the selected entry is deleted.
	 * This continues until either a unit is generated or there are no remaining entries.
	 * 
	 * @param filter Passed to generateUnit() in the generated table.
	 * @return A unit generated from another faction, or null if none of the factions in
	 * 	       the salvage list contain any units that meet the parameters.
	 */
	private MechSummary generateSalvage(UnitFilter filter) {
		while (salvageTotal > 0) {
			int roll = Compute.randomInt(salvageTotal);
			FactionRecord fRec = salvageMap.floorEntry(roll).getValue();
			UnitTable salvage = salvageCache.get(fRec.getKey());
			if (salvage == null) {
				salvage = new UnitTable(fRec,
						unitType, year - 5, rating, weightClasses, networkMask, subtypes,
						roles, roleStrictness, faction);
			}
			if (salvage.hasUnits()) {
				salvageCache.put(fRec.getKey(), salvage);
				return salvage.generateUnit(filter);
			} else {
				int index = -1;
				for (int i = 0; i < table.size(); i++) {
					if (!table.get(i).isUnit() && table.get(i).getSalvageFaction().getKey().equals(fRec.getKey())) {
						index = i;
						break;
					}
				}
				if (index >= 0) {
					table.remove(index);
				}
				/* Rebuild the table */
				salvageMap.clear();
				salvageTotal = 0;
				for (TableEntry entry : table) {
					if (!entry.isUnit()) {
						salvageMap.put(salvageTotal, entry.getSalvageFaction());
						salvageTotal += entry.weight;
					}
				}
			}					
		}
		assert(salvageMap.isEmpty() && salvageTotal == 0);
		return null;
	}
	
	/* A tuple that contains either a salvage or a faction entry along with its relative weight.
	 * in the table. */
	public static class TableEntry implements Comparable<TableEntry> {
		int weight;
		Object entry;
		
		public TableEntry(int weight, Object entry) {
			this.weight = weight;
			this.entry = entry;
		}
		
		public MechSummary getUnitEntry() {
			return (MechSummary)entry;
		}
		
		public FactionRecord getSalvageFaction() {
			return (FactionRecord)entry;
		}
		
		public boolean isUnit() {
			return entry instanceof MechSummary;
		}

		@Override
		public int compareTo(TableEntry other) {
			if (entry instanceof MechSummary && other.entry instanceof FactionRecord) {
				return 1;
			}
			if (entry instanceof FactionRecord && other.entry instanceof MechSummary) {
				return -1;
			}
			return toString().compareTo(other.toString());
		}
			
		@Override
		public String toString() {
			if (entry instanceof MechSummary) {
				return ((MechSummary)entry).getName();
			}
			return entry.toString();
		}
	}
}
