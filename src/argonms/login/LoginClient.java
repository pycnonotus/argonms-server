/*
 * ArgonMS MapleStory server emulator written in Java
 * Copyright (C) 2011  GoldenKevin
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package argonms.login;

import argonms.ServerType;
import argonms.character.Player;
import argonms.net.HashFunctions;
import argonms.net.client.CommonPackets;
import argonms.net.client.RemoteClient;
import argonms.tools.DatabaseConnection;
import argonms.tools.output.LittleEndianWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author GoldenKevin
 */
public class LoginClient extends RemoteClient {
	private static final Logger LOG = Logger.getLogger(LoginClient.class.getName());

	public static final byte
		GENDER_MALE = 0,
		GENDER_FEMALE = 1,
		GENDER_UNDEFINED = 0x0A //lawl
	;

	private String pin;
	private byte gender;
	private int birthday;
	private byte chars;
	private int banExpire;
	private byte banReason;
	private byte gm;
	private boolean serverTransition;

	/*
	 * 0 = ok
	 * 2 = banned
	 * 4 = wrong password
	 * 5 = no account exists
	 * 7 = already logged in
	 * 8 = system error
	 * Maybe we shouldn't reload all of the account data if we already tried
	 * logging in during this session.
	 */
	public byte loginResult(String pwd) {
		boolean hashUpdate = false;
		byte result = 0;
		Connection con = DatabaseConnection.getConnection();
		try {
			PreparedStatement ps = con.prepareStatement("SELECT `id`,`password`,`salt`,`pin`,`gender`,`birthday`,`characters`,`connected`,`banexpire`,`banreason`,`gm` FROM `accounts` WHERE `name` = ?");
			ps.setString(1, getAccountName());
			ResultSet rs = ps.executeQuery();
			if (rs.next()) {
				setAccountId(rs.getInt(1));
				String passhash = rs.getString(2);
				String salt = rs.getString(3);
				pin = rs.getString(4);
				gender = rs.getByte(5);
				if (rs.wasNull())
					gender = GENDER_UNDEFINED;
				birthday = rs.getInt(6);
				chars = rs.getByte(7);
				if (rs.wasNull())
					chars = 3;
				byte onlineStatus = rs.getByte(8);
				banExpire = rs.getInt(9);
				banReason = rs.getByte(10);
				gm = rs.getByte(11);
				rs.close();
				ps.close();
				if ((salt == null || salt.length() == 0) && (passhash.equals(pwd) || HashFunctions.checkSha1Hash(passhash, pwd))) {
					hashUpdate = true;
				} else if (!HashFunctions.checkSaltedSha512Hash(passhash, pwd, salt)) {
					result = 4;
				} else if (onlineStatus != STATUS_NOTLOGGEDIN) {
					//TODO: there is a high chance that the player is not really
					//in game if they have an onlineStatus of STATUS_MIGRATION.
					//Make a way to check if they really are/will be connected
					//to a server
					result = 7;
				} else if (banExpire > 0) {
					result = 2;
				}

				if (hashUpdate) {
					salt = HashFunctions.makeSalt();
					passhash = HashFunctions.makeSaltedSha512Hash(pwd, salt);
					ps = con.prepareStatement("UPDATE `accounts` SET `connected` = ?, `password` = ?, `salt` = ? WHERE `id` = ?");
					ps.setByte(1, STATUS_INLOGIN);
					ps.setString(2, passhash);
					ps.setString(3, salt);
					ps.setInt(4, getAccountId());
				} else {
					ps = con.prepareStatement("UPDATE `accounts` SET `connected` = ? WHERE `id` = ?");
					ps.setByte(1, STATUS_INLOGIN);
					ps.setInt(2, getAccountId());
				}
				ps.executeUpdate();
				ps.close();
			} else {
				result = 5;
			}
		} catch (SQLException ex) {
			LOG.log(Level.WARNING, "Could not fetch login information of account " + getAccountName(), ex);
			result = 8;
		}
		return result;
	}

	public byte getGender() {
		return gender;
	}

	public byte getMaxCharacters() {
		return chars;
	}

	public byte getBanReason() {
		return banReason;
	}

	public int getBanExpiration() {
		return banExpire;
	}

	public String getPin() {
		return pin;
	}

	public byte getGm() {
		return gm;
	}

	public void setGender(byte gender) {
		this.gender = gender;
		Connection con = DatabaseConnection.getConnection();
		try {
			PreparedStatement ps = con.prepareStatement("UPDATE `accounts` SET `gender` = ? WHERE `id` = ?");
			ps.setByte(1, gender);
			ps.setInt(2, getAccountId());
			ps.executeUpdate();
			ps.close();
		} catch (SQLException ex) {
			LOG.log(Level.WARNING, "Could not set gender of account " + getAccountId(), ex);
		}
	}

	public void setPin(String pin) {
		this.pin = pin;
		Connection con = DatabaseConnection.getConnection();
		try {
			PreparedStatement ps = con.prepareStatement("UPDATE `accounts` SET `pin` = ? WHERE `id` = ?");
			ps.setString(1, pin);
			ps.setInt(2, getAccountId());
			ps.executeUpdate();
			ps.close();
		} catch (SQLException ex) {
			LOG.log(Level.WARNING, "Could not set pin of account " + getAccountId(), ex);
		}
	}

	public void addCharacters(LittleEndianWriter lew) {
		Connection con = DatabaseConnection.getConnection();
		//TODO: There's gotta be a way to not have to query the database twice?
		try {
			PreparedStatement ps = con.prepareStatement("SELECT count(*) FROM `characters` WHERE `accountid` = ? AND `world` = ?");
			ps.setInt(1, getAccountId());
			ps.setInt(2, getWorld());
			ResultSet rs = ps.executeQuery();
			if (rs.next())
				lew.writeByte(rs.getByte(1));
			rs.close();
			ps.close();

			ps = con.prepareStatement("SELECT `id` FROM `characters` WHERE `accountid` = ? AND `world` = ?");
			ps.setInt(1, getAccountId());
			ps.setInt(2, getWorld());
			rs = ps.executeQuery();
			while (rs.next())
				CommonPackets.writeCharEntry(lew, Player.loadPlayer(this, rs.getInt(1)));
			rs.close();
			ps.close();
		} catch (SQLException ex) {
			LOG.log(Level.WARNING, "Could not load characters of account " + getAccountId(), ex);
		}
	}

	//TODO: Find more status codes.
	public byte deleteCharacter(int characterid, int enteredBirthday) {
		byte status = 18;
		if (birthday == 0 || birthday == enteredBirthday) {
			Connection con = DatabaseConnection.getConnection();
			try {
				PreparedStatement ps = con.prepareStatement("DELETE FROM `characters` WHERE `id` = ?");
				ps.setInt(1, characterid);
				int rowsUpdated = ps.executeUpdate();
				ps.close();
				if (rowsUpdated != 0)
					status = 0;
				else
					status = 1;
			} catch (SQLException ex) {
				LOG.log(Level.WARNING, "Could not delete character " + characterid + " of account " + getAccountId(), ex);
				status = 1;
			}
		}
		return status;
	}

	//TODO: I guess we're gonna have to save the mac address to the SQL so we
	//can unban them when they're not logged in...
	public boolean hasBannedMac(String macData) {
		String[] macAddresses = macData.split(", ");
		StringBuilder query = new StringBuilder("SELECT COUNT(*) FROM `macbans` WHERE `mac` IN (");
		for (int i = 0; i < macAddresses.length; i++)
			query.append("?, ");

		Connection con = DatabaseConnection.getConnection();
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = con.prepareStatement(query.replace(query.length() - 2, query.length(), ")").toString());
			for (int i = 0; i < macAddresses.length; i++)
				ps.setString(i + 1, macAddresses[i]);
			rs = ps.executeQuery();
			if (rs.next() && rs.getInt(1) > 0)
				return true;
		} catch (SQLException e) {
			LOG.log(Level.WARNING, "Could not update MAC addresses of account " + getAccountId(), e);
		} finally {
			try {
				if (rs != null)
					rs.close();
				if (ps != null)
					ps.close();
			} catch (SQLException ex) {
				//nothing we can do...
			}
		}
		return false;
	}

	public void hostMigration() {
		serverTransition = true;
		updateState(STATUS_MIGRATION);
	}

	public byte getServerType() {
		return ServerType.LOGIN;
	}

	public void disconnect() {
		stopPingTask();
		if (!serverTransition && getAccountId() != 0)
			updateState(STATUS_NOTLOGGEDIN);
	}
}