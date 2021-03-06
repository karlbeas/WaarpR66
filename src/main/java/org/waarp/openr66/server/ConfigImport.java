/**
 * This file is part of Waarp Project.
 * 
 * Copyright 2009, Frederic Bregier, and individual contributors by the @author tags. See the
 * COPYRIGHT.txt in the distribution for a full listing of individual contributors.
 * 
 * All Waarp Project is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 * 
 * Waarp is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with Waarp . If not, see
 * <http://www.gnu.org/licenses/>.
 */
package org.waarp.openr66.server;

import java.net.SocketAddress;

import org.jboss.netty.channel.Channels;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.waarp.common.logging.WaarpInternalLogger;
import org.waarp.common.logging.WaarpInternalLoggerFactory;
import org.waarp.common.logging.WaarpSlf4JLoggerFactory;
import org.waarp.openr66.configuration.FileBasedConfiguration;
import org.waarp.openr66.context.ErrorCode;
import org.waarp.openr66.context.R66FiniteDualStates;
import org.waarp.openr66.context.R66Result;
import org.waarp.openr66.database.DbConstant;
import org.waarp.openr66.database.data.DbHostAuth;
import org.waarp.openr66.protocol.configuration.Configuration;
import org.waarp.openr66.protocol.exception.OpenR66ProtocolNoConnectionException;
import org.waarp.openr66.protocol.exception.OpenR66ProtocolPacketException;
import org.waarp.openr66.protocol.localhandler.LocalChannelReference;
import org.waarp.openr66.protocol.localhandler.packet.LocalPacketFactory;
import org.waarp.openr66.protocol.localhandler.packet.ValidPacket;
import org.waarp.openr66.protocol.networkhandler.NetworkTransaction;
import org.waarp.openr66.protocol.utils.ChannelUtils;
import org.waarp.openr66.protocol.utils.R66Future;

/**
 * Config Import from a local client without database connection
 * 
 * @author Frederic Bregier
 * 
 */
public class ConfigImport implements Runnable {
	/**
	 * Internal Logger
	 */
	static volatile WaarpInternalLogger logger;

	protected final R66Future future;
	protected final String host;
	protected final boolean hostPurge;
	protected final String rule;
	protected final boolean rulePurge;
	protected final NetworkTransaction networkTransaction;

	public ConfigImport(R66Future future, boolean hostPurge, boolean rulePurge,
			String host, String rule,
			NetworkTransaction networkTransaction) {
		this.future = future;
		this.host = host;
		this.rule = rule;
		this.hostPurge = hostPurge;
		this.rulePurge = rulePurge;
		this.networkTransaction = networkTransaction;
	}

	/**
	 * Prior to call this method, the pipeline and NetworkTransaction must have been initialized. It
	 * is the responsibility of the caller to finish all network resources.
	 */
	public void run() {
		if (logger == null) {
			logger = WaarpInternalLoggerFactory.getLogger(ConfigImport.class);
		}
		ValidPacket valid = new ValidPacket((hostPurge ? "1 " : "0 ") + host,
				(rulePurge ? "1 " : "0 ") + rule,
				LocalPacketFactory.CONFIMPORTPACKET);
		DbHostAuth host = Configuration.configuration.HOST_SSLAUTH;
		SocketAddress socketAddress = host.getSocketAddress();
		boolean isSSL = host.isSsl();

		LocalChannelReference localChannelReference = networkTransaction
				.createConnectionWithRetry(socketAddress, isSSL, future);
		socketAddress = null;
		if (localChannelReference == null) {
			host = null;
			logger.error("Cannot Connect");
			future.setResult(new R66Result(
					new OpenR66ProtocolNoConnectionException("Cannot connect to server"),
					null, true, ErrorCode.Internal, null));
			future.setFailure(future.getResult().exception);
			return;
		}
		localChannelReference.sessionNewState(R66FiniteDualStates.VALIDOTHER);
		try {
			ChannelUtils.writeAbstractLocalPacket(localChannelReference, valid, false);
		} catch (OpenR66ProtocolPacketException e) {
			logger.error("Bad Protocol", e);
			Channels.close(localChannelReference.getLocalChannel());
			localChannelReference = null;
			host = null;
			valid = null;
			future.setResult(new R66Result(e, null, true,
					ErrorCode.TransferError, null));
			future.setFailure(e);
			return;
		}
		host = null;
		future.awaitUninterruptibly();
		logger.debug("Request done with " + (future.isSuccess() ? "success" : "error"));
		Channels.close(localChannelReference.getLocalChannel());
		localChannelReference = null;
	}

	protected static String shost = null;
	protected static String srule = null;
	protected static boolean shostpurge = false;
	protected static boolean srulepurge = false;

	protected static boolean getParams(String[] args) {
		if (args.length < 3) {
			logger.error("Need at least the configuration file as first argument then at least one from\n"
					+
					"    -hosts file\n" +
					"    -rules file\n" +
					"    -purgehosts\n" +
					"    -purgerules");
			return false;
		}
		if (!FileBasedConfiguration
				.setClientConfigurationFromXml(Configuration.configuration, args[0])) {
			logger.error("Need at least the configuration file as first argument then at least one from\n"
					+
					"    -hosts file\n" +
					"    -rules file\n" +
					"    -purgehosts\n" +
					"    -purgerules");
			return false;
		}
		for (int i = 1; i < args.length; i++) {
			if (args[i].equalsIgnoreCase("-hosts")) {
				i++;
				if (args.length <= i) {
					return false;
				}
				shost = args[i];
			} else if (args[i].equalsIgnoreCase("-rules")) {
				i++;
				if (args.length <= i) {
					return false;
				}
				srule = args[i];
			} else if (args[i].equalsIgnoreCase("-purgehosts")) {
				shostpurge = true;
			} else if (args[i].equalsIgnoreCase("-purgerules")) {
				srulepurge = true;
			}
		}
		if ((shost == null) && (srule == null)) {
			logger.error("Need at least one of -hosts - rules");
			return false;
		}
		return true;
	}

	public static void main(String[] args) {
		InternalLoggerFactory.setDefaultFactory(new WaarpSlf4JLoggerFactory(null));
		if (logger == null) {
			logger = WaarpInternalLoggerFactory.getLogger(ConfigImport.class);
		}
		if (!getParams(args)) {
			logger.error("Wrong initialization");
			if (DbConstant.admin != null && DbConstant.admin.isConnected) {
				DbConstant.admin.close();
			}
			System.exit(1);
		}
		long time1 = System.currentTimeMillis();
		R66Future future = new R66Future(true);

		Configuration.configuration.pipelineInit();
		NetworkTransaction networkTransaction = new NetworkTransaction();
		try {
			ConfigImport transaction = new ConfigImport(future,
					shostpurge, srulepurge, shost, srule,
					networkTransaction);
			transaction.run();
			future.awaitUninterruptibly();
			long time2 = System.currentTimeMillis();
			long delay = time2 - time1;
			R66Result result = future.getResult();
			if (future.isSuccess()) {
				if (result.code == ErrorCode.Warning) {
					logger.warn("WARNED on import:\n    " +
							(result.other != null ? ((ValidPacket) result.other).getSheader() :
									"no import")
							+ "\n    delay: " + delay);
				} else {
					logger.warn("SUCCESS on import:\n    " +
							(result.other != null ? ((ValidPacket) result.other).getSheader() :
									"no import")
							+ "\n    delay: " + delay);
				}
			} else {
				if (result.code == ErrorCode.Warning) {
					logger.warn("Transfer is\n    WARNED", future.getCause());
					networkTransaction.closeAll();
					System.exit(result.code.ordinal());
				} else {
					logger.error("Transfer in\n    FAILURE", future.getCause());
					networkTransaction.closeAll();
					System.exit(result.code.ordinal());
				}
			}
		} finally {
			networkTransaction.closeAll();
		}
	}

}
