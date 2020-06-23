package com.palmergames.bukkit.towny.war.siegewar.utils;

import com.palmergames.bukkit.towny.*;
import com.palmergames.bukkit.towny.exceptions.NotRegisteredException;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.war.siegewar.objects.BattleSession;
import com.palmergames.bukkit.util.BukkitTools;
import com.palmergames.util.TimeMgmt;
import com.palmergames.util.TimeTools;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;

public class SiegeWarBattleSessionUtil {

	public static final String METADATA_TAG_NAME= "towny.siegewar.battle.session";
	
	public static void evaluateBattleSessions() {
		BattleSession battleSession = null;
		
		for(Player player: BukkitTools.getOnlinePlayers()) {
			try {
				//Process progress of existing session
				if (player.hasMetadata(METADATA_TAG_NAME)) {
					battleSession = (BattleSession)player.getMetadata(METADATA_TAG_NAME).get(0).value();
					if(battleSession.isExpired()) {
						//Expired Session found. If deletion time has arrived, nuke session
						if(System.currentTimeMillis() >= battleSession.getDeletionTime()) {
							player.removeMetadata(METADATA_TAG_NAME, Towny.getPlugin());
							TownyMessaging.sendMsg(player, TownySettings.getLangString("msg_war_siege_battle_session_ended"));
						}
					} else {
						//Active Session found. If expiry time has arrived, set session to expired
						if(System.currentTimeMillis() >= battleSession.getExpiryTime()) {
							battleSession.setExpired(true);
							battleSession.setDeletionTime(System.currentTimeMillis() + (int)(TownySettings.getWarSiegeBattleSessionsExpiredPhaseDurationMinutes() * TimeMgmt.ONE_MINUTE_IN_MILLIS));
						}
					}
				}

				boolean playerInOwnTown = false;
				try {
					Resident resident = TownyUniverse.getInstance().getDataSource().getResident(player.getName());
					if(resident.hasTown()) {
						TownBlock townBlockAtPlayerLocation = TownyAPI.getInstance().getTownBlock(player.getLocation());
						if(townBlockAtPlayerLocation != null) {
							if(resident.getTown() == townBlockAtPlayerLocation.getTown()) {
								playerInOwnTown = true;
							}
						}
					}
				} catch (NotRegisteredException nre) {}

				//If player is in a siegezone (& not in own town), process initiation/effects
				if(!playerInOwnTown && SiegeWarDistanceUtil.isLocationInActiveSiegeZone(player.getLocation())) {
					if (!player.hasMetadata(METADATA_TAG_NAME)) {
						battleSession = new BattleSession();
						battleSession.setExpiryTime(System.currentTimeMillis() + (int)(TownySettings.getWarSiegeBattleSessionsActivePhaseDurationMinutes() * TimeMgmt.ONE_MINUTE_IN_MILLIS));
						player.setMetadata(METADATA_TAG_NAME, new FixedMetadataValue(Towny.getPlugin(), battleSession));
						long timeRemainingMillis = (int)(TownySettings.getWarSiegeBattleSessionsActivePhaseDurationMinutes() * TimeMgmt.ONE_MINUTE_IN_MILLIS);
						String timeRemainingString = TimeMgmt.getFormattedTimeValue(timeRemainingMillis);
						TownyMessaging.sendMsg(player, String.format(TownySettings.getLangString("msg_war_siege_battle_session_started"), timeRemainingString));
						continue;
					}

					//Check effect of expired session
					if(battleSession.isExpired()) {
						long timeRemainingMillis = battleSession.getDeletionTime() - System.currentTimeMillis();
						String timeRemainingString = TimeMgmt.getFormattedTimeValue(timeRemainingMillis);

						if(battleSession.isWarningGiven()) {
							TownyMessaging.sendMsg(player, String.format(TownySettings.getLangString("msg_war_siege_battle_session_expired"), timeRemainingString));
							int effectDurationTicks = (int)(TimeTools.convertToTicks(TownySettings.getShortInterval() + 5));
							Towny.getPlugin().getServer().getScheduler().scheduleSyncDelayedTask(Towny.getPlugin(), new Runnable() {
								public void run() {
									List<PotionEffect> potionEffects = new ArrayList<>();
									potionEffects.add(new PotionEffect(PotionEffectType.POISON, effectDurationTicks, 1));
									potionEffects.add(new PotionEffect(PotionEffectType.SLOW, effectDurationTicks, 1));
									potionEffects.add(new PotionEffect(PotionEffectType.WEAKNESS, effectDurationTicks, 1));
									player.addPotionEffects(potionEffects);
								}
							});
						} else {
							battleSession.setWarningGiven(true);
							TownyMessaging.sendMsg(player, String.format(TownySettings.getLangString("msg_war_siege_battle_session_expired_warning"), timeRemainingString));
						}
					}

				} else {
					//Player not in siege zone, or in own town. Reset warning if applicable
					if(battleSession != null && battleSession.isExpired() && battleSession.isWarningGiven()) {
						battleSession.setWarningGiven(false);
					}

				}
			} catch (Exception e) {
				try {
					System.out.println("Problem evaluating battle session player " + player.getName());
				} catch (Exception e2) {
					System.out.println("Problem evaluating battle session for a player (could not read player name)");
				}
				e.printStackTrace();
			}
		}
	}
}
