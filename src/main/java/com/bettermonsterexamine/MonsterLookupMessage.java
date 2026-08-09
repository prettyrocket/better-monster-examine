package com.bettermonsterexamine;

import java.util.Map;
import lombok.Getter;

/**
 * A parsed inbound {@code bettermonsterexamine}/{@code displayMonster} request — the return leg of the
 * Not Enough Runes link, and the entry point for any plugin that wants to open a monster here.
 *
 * <p>Sibling classloaders mean nothing but core types can cross the boundary, so the payload is a
 * {@code Map} of strings and numbers and every read is type-checked rather than cast. Numbers are
 * taken as {@link Number} rather than {@code Integer} so a sender that boxes differently still works.
 *
 * <p>The contract:
 *
 * <table>
 *   <tr><th>key</th><th>type</th><th>required</th></tr>
 *   <tr><td>{@code name}</td><td>String</td><td>unless {@code npcId} is given</td></tr>
 *   <tr><td>{@code level}</td><td>Number</td><td>no — in-game combat level, picks a variant</td></tr>
 *   <tr><td>{@code npcId}</td><td>Number</td><td>no — preferred when present, it beats name matching</td></tr>
 *   <tr><td>{@code tab}</td><td>String</td><td>no — {@code "stats"} (default) or {@code "drops"}</td></tr>
 * </table>
 *
 * <p>Unknown keys are ignored, so the contract can grow without either side having to ship in step.
 */
@Getter
final class MonsterLookupMessage
{
	static final String NAMESPACE = "bettermonsterexamine";
	static final String DISPLAY_MONSTER = "displayMonster";

	private static final String KEY_NAME = "name";
	private static final String KEY_LEVEL = "level";
	private static final String KEY_NPC_ID = "npcId";
	private static final String KEY_TAB = "tab";
	private static final String TAB_DROPS = "drops";

	/** The requested NPC id, or null. Resolves to a variant outright, so it wins over name + level. */
	private final Integer npcId;
	/** The requested monster name, trimmed; null when the request only carried an {@code npcId}. */
	private final String name;
	/** The in-game combat level used to pick a variant, or null to take the default one. */
	private final Integer level;
	/** True when the sender asked for the Drops tab; Stats otherwise. */
	private final boolean drops;

	private MonsterLookupMessage(Integer npcId, String name, Integer level, boolean drops)
	{
		this.npcId = npcId;
		this.name = name;
		this.level = level;
		this.drops = drops;
	}

	/**
	 * Parse a request, or return null when it carries nothing to act on — neither an {@code npcId} nor
	 * a non-blank {@code name}. A wrongly-typed value is treated as absent rather than throwing: a
	 * malformed message from another plugin should be ignored, not crash the event bus.
	 */
	static MonsterLookupMessage of(Map<String, Object> data)
	{
		if (data == null)
		{
			return null;
		}
		Integer npcId = intOrNull(data.get(KEY_NPC_ID));
		String name = data.get(KEY_NAME) instanceof String ? ((String) data.get(KEY_NAME)).trim() : null;
		if (name != null && name.isEmpty())
		{
			name = null;
		}
		if (npcId == null && name == null)
		{
			return null;
		}
		Object tab = data.get(KEY_TAB);
		boolean drops = tab instanceof String && TAB_DROPS.equalsIgnoreCase(((String) tab).trim());
		return new MonsterLookupMessage(npcId, name, intOrNull(data.get(KEY_LEVEL)), drops);
	}

	private static Integer intOrNull(Object value)
	{
		return value instanceof Number ? ((Number) value).intValue() : null;
	}
}
