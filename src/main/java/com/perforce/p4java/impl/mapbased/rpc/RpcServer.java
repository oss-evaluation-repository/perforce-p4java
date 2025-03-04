/*
 * Copyright (c) 2009-2022 Perforce Software Inc., All Rights Reserved.
 */
package com.perforce.p4java.impl.mapbased.rpc;

import com.perforce.p4java.Log;
import com.perforce.p4java.PropertyDefs;
import com.perforce.p4java.env.PerforceEnvironment;
import com.perforce.p4java.exception.AccessException;
import com.perforce.p4java.exception.ConfigException;
import com.perforce.p4java.exception.ConnectionException;
import com.perforce.p4java.exception.P4JavaException;
import com.perforce.p4java.exception.RequestException;
import com.perforce.p4java.exception.TrustException;
import com.perforce.p4java.impl.mapbased.rpc.connection.RpcConnection;
import com.perforce.p4java.impl.mapbased.rpc.func.client.ClientTrust;
import com.perforce.p4java.impl.mapbased.rpc.func.proto.PerformanceMonitor;
import com.perforce.p4java.impl.mapbased.rpc.func.proto.ProtocolCommand;
import com.perforce.p4java.impl.mapbased.rpc.helper.RpcUserAuthCounter;
import com.perforce.p4java.impl.mapbased.rpc.packet.helper.RpcPacketFieldRule;
import com.perforce.p4java.impl.mapbased.rpc.stream.RpcStreamConnection;
import com.perforce.p4java.impl.mapbased.server.Server;
import com.perforce.p4java.impl.mapbased.server.cmd.ResultMapParser;
import com.perforce.p4java.messages.PerforceMessages;
import com.perforce.p4java.option.UsageOptions;
import com.perforce.p4java.option.server.TrustOptions;
import com.perforce.p4java.server.AbstractAuthHelper;
import com.perforce.p4java.server.AuthTicketsHelper;
import com.perforce.p4java.server.CmdSpec;
import com.perforce.p4java.server.Fingerprint;
import com.perforce.p4java.server.FingerprintsHelper;
import com.perforce.p4java.server.IServerAddress;
import com.perforce.p4java.server.IServerImplMetadata;
import com.perforce.p4java.server.IServerInfo;
import com.perforce.p4java.server.P4Charset;
import com.perforce.p4java.server.ServerStatus;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.Validate;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static com.perforce.p4java.PropertyDefs.AUTH_FILE_LOCK_DELAY_KEY;
import static com.perforce.p4java.PropertyDefs.AUTH_FILE_LOCK_DELAY_KEY_SHORT_FORM;
import static com.perforce.p4java.PropertyDefs.AUTH_FILE_LOCK_TRY_KEY;
import static com.perforce.p4java.PropertyDefs.AUTH_FILE_LOCK_TRY_KEY_SHORT_FORM;
import static com.perforce.p4java.PropertyDefs.AUTH_FILE_LOCK_WAIT_KEY;
import static com.perforce.p4java.PropertyDefs.AUTH_FILE_LOCK_WAIT_KEY_SHORT_FORM;
import static com.perforce.p4java.PropertyDefs.TICKET_PATH_KEY;
import static com.perforce.p4java.PropertyDefs.TICKET_PATH_KEY_SHORT_FORM;
import static com.perforce.p4java.PropertyDefs.TRUST_PATH_KEY;
import static com.perforce.p4java.PropertyDefs.TRUST_PATH_KEY_SHORT_FORM;
import static com.perforce.p4java.PropertyDefs.WRITE_IN_PLACE_KEY;
import static com.perforce.p4java.PropertyDefs.WRITE_IN_PLACE_SHORT_FORM;
import static com.perforce.p4java.common.base.P4JavaExceptions.throwConnectionExceptionIfConditionFails;
import static com.perforce.p4java.common.base.P4ResultMapUtils.parseCode0ErrorString;
import static com.perforce.p4java.common.base.StringHelper.firstConditionIsTrue;
import static com.perforce.p4java.common.base.StringHelper.firstNonBlank;
import static com.perforce.p4java.exception.MessageGenericCode.EV_NONE;
import static com.perforce.p4java.exception.MessageSeverityCode.E_EMPTY;
import static com.perforce.p4java.exception.MessageSeverityCode.E_FAILED;
import static com.perforce.p4java.exception.MessageSeverityCode.E_INFO;
import static com.perforce.p4java.exception.TrustException.Type.NEW_CONNECTION;
import static com.perforce.p4java.exception.TrustException.Type.NEW_KEY;
import static com.perforce.p4java.impl.mapbased.rpc.RpcPropertyDefs.RPC_APPLICATION_NAME_NICK;
import static com.perforce.p4java.impl.mapbased.rpc.RpcPropertyDefs.RPC_RELAX_CMD_NAME_CHECKS_NICK;
import static com.perforce.p4java.impl.mapbased.rpc.RpcPropertyDefs.getPropertyAsBoolean;
import static com.perforce.p4java.impl.mapbased.rpc.func.client.ClientTrust.CLIENT_TRUST_ADDED;
import static com.perforce.p4java.impl.mapbased.rpc.func.client.ClientTrust.CLIENT_TRUST_ADD_EXCEPTION_NEW_CONNECTION;
import static com.perforce.p4java.impl.mapbased.rpc.func.client.ClientTrust.CLIENT_TRUST_ADD_EXCEPTION_NEW_KEY;
import static com.perforce.p4java.impl.mapbased.rpc.func.client.ClientTrust.CLIENT_TRUST_ALREADY_ESTABLISHED;
import static com.perforce.p4java.impl.mapbased.rpc.func.client.ClientTrust.CLIENT_TRUST_EXCEPTION_NEW_CONNECTION;
import static com.perforce.p4java.impl.mapbased.rpc.func.client.ClientTrust.CLIENT_TRUST_EXCEPTION_NEW_KEY;
import static com.perforce.p4java.impl.mapbased.rpc.func.client.ClientTrust.CLIENT_TRUST_REMOVED;
import static com.perforce.p4java.impl.mapbased.rpc.func.client.ClientTrust.CLIENT_TRUST_WARNING_NEW_CONNECTION;
import static com.perforce.p4java.impl.mapbased.rpc.func.client.ClientTrust.CLIENT_TRUST_WARNING_NEW_KEY;
import static com.perforce.p4java.impl.mapbased.rpc.func.client.ClientTrust.CLIENT_TRUST_WARNING_NOT_ESTABLISHED;
import static com.perforce.p4java.impl.mapbased.rpc.func.client.ClientTrust.FINGERPRINT_REPLACEMENT_USER_NAME;
import static com.perforce.p4java.impl.mapbased.rpc.func.client.ClientTrust.FINGERPRINT_USER_NAME;
import static com.perforce.p4java.impl.mapbased.rpc.msg.RpcMessage.getGeneric;
import static com.perforce.p4java.impl.mapbased.rpc.msg.RpcMessage.getSeverity;
import static com.perforce.p4java.server.CmdSpec.LOGIN;
import static com.perforce.p4java.server.CmdSpec.LOGIN2;
import static com.perforce.p4java.server.CmdSpec.getValidP4JCmdSpec;
import static com.perforce.p4java.util.PropertiesHelper.getPropertyAsInt;
import static com.perforce.p4java.util.PropertiesHelper.getPropertyAsLong;
import static com.perforce.p4java.util.PropertiesHelper.getPropertyByKeys;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.apache.commons.lang3.StringUtils.contains;
import static org.apache.commons.lang3.StringUtils.indexOf;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * RPC-based Perforce server implementation superclass.
 */
public abstract class RpcServer extends Server {
	/**
	 * The implementation type of this implementation.
	 */
	public static final IServerImplMetadata.ImplType IMPL_TYPE = IServerImplMetadata.ImplType.NATIVE_RPC;

	/**
	 * The default string sent to the Perforce server in the protocol command
	 * defining the client's program name. This can be set with the IServer
	 * interface.
	 */
	public static final String DEFAULT_PROG_NAME = "p4jrpc";

	/**
	 * The default string sent to the Perforce server in the protocol command
	 * defining the client's program version. This can be set with the IServer
	 * interface.
	 */
	public static final String DEFAULT_PROG_VERSION = "Beta 1.0";

	/**
	 * Default Perforce client API level; 81 represents 2016.2 capabilities.
	 * Don't change this unless you know what you're doing. Note that this is a
	 * default for most commands; some commands dynamically bump up the level
	 * for the command's duration.
	 */
	public static final int DEFAULT_CLIENT_API_LEVEL = 92; // 2022.1

	// p4/msgs/p4tagl.cc
	// p4/client/client.cc
	// p4/server/rhservice.h

	/**
	 * Default Perforce server API level; 99999 apparently means "whatever...".
	 * Don't change this unless you know what you're doing.
	 */
	public static final int DEFAULT_SERVER_API_LEVEL = 99999; // As picked off
	// the wire for
	// C++ API
	// p4/client/clientmain.cc
	// p4/server/rhservice.cc

	/**
	 * Signifies whether or not we use tagged output. Don't change this unless
	 * you like weird incomprehensible errors and days of debugging.
	 */
	public static final boolean RPC_TAGS_USED = true;

	/**
	 * Signifies whether or not the client is capable of handling streams.
	 */
	public static final boolean RPC_ENABLE_STREAMS = true;

	/**
	 * The system properties key for the JVM's current directory.
	 */
	public static final String RPC_ENV_CWD_KEY = "user.dir";

	/**
	 * The system properties key for the OS name.
	 */
	public static final String RPC_ENV_OS_NAME_KEY = "os.name";

	/**
	 * RPC_ENV_OS_NAME_KEY property value prefix for Windows systems.
	 */
	public static final String RPC_ENV_WINDOWS_PREFIX = "windows";

	/**
	 * What we use in the RPC environment packet to signal to the Perforce
	 * server that we're a Windows box.
	 */
	public static final String RPC_ENV_WINDOWS_SPEC = "NT"; // sic

	/**
	 * What we use in the RPC environment packet to signal to the Perforce
	 * server that we're a NON-Windows box.
	 */

	public static final String RPC_ENV_UNIX_SPEC = "UNIX";

	/**
	 * What we use in the RPC environment packet to signal to the Perforce
	 * server that we don't have a client set yet or don't know what it is.
	 */
	public static final String RPC_ENV_NOCLIENT_SPEC = "unknownclient";

	/**
	 * What we use in the RPC environment packet to signal to the Perforce
	 * server that we don't have a hostname set yet or don't know what it is.
	 */
	public static final String RPC_ENV_NOHOST_SPEC = "nohost"; // as cribbed
	// from the C++
	// API...

	/**
	 * What we use in the RPC environment packet to signal to the Perforce
	 * server that we don't have a client set yet or don't know what it is.
	 */
	public static final String RPC_ENV_NOUSER_SPEC = "nouser"; // as cribbed
	// from the C++
	// API...

	/**
	 * What we use as a P4JTracer trace prefix for methods here.
	 */
	public static final String TRACE_PREFIX = "RpcServer";

	/**
	 * Used to key temporary output streams in the command environment's state
	 * map for things like getFileContents(), etc., using the execStreamCmd
	 * method(s).
	 */
	public static final String RPC_TMP_OUTFILE_STREAM_KEY = "";

    public static final String RPC_BYTE_BUFFER_OUTPUT_KEY = "";

	/**
	 * Use to key converter to use out of state map
	 */
	public static final String RPC_TMP_CONVERTER_KEY = "RPC_TMP_CONVERTER_KEY";

	private static final String AUTH_FAIL_STRING_1 = "Single sign-on on client failed"; // SSO
	// failure
	private static final String AUTH_FAIL_STRING_2 = "Password invalid";

	private static final String[] ACCESS_ERROR_MSGS = {CORE_AUTH_FAIL_STRING_1, CORE_AUTH_FAIL_STRING_2, CORE_AUTH_FAIL_STRING_3, CORE_AUTH_FAIL_STRING_4, AUTH_FAIL_STRING_1, AUTH_FAIL_STRING_2};

	private static final String PASSWORD_NOT_SET_STRING = "no password set for this user";

	protected String localHostName = null; // intended to be just the
	// unqualified name...
	protected int clientApiLevel = DEFAULT_CLIENT_API_LEVEL;
	protected int serverApiLevel = DEFAULT_SERVER_API_LEVEL;
	protected String applicationName = null; // Application name

	protected long connectionStart = 0;

	protected Map<String, Object> serverProtocolMap = new HashMap<>();

	protected ServerStats serverStats = null;

	protected String serverId = null;

	protected Map<String, String> secretKeys = new HashMap<>();

	protected Map<String, String> pBufs = new HashMap<>();

	protected ClientTrust clientTrust = null;

	protected String ticketsFilePath = null;

	protected String trustFilePath = null;

	protected boolean validatedByChain = false;

	/**
	 * was the server ssl connection validated by chain?
	 *
	 * @return true if it's an ssl connection with valid chain
	 */
	public boolean isValidatedByChain() {
		if (!isSecure()) {
			return false;
		}
		return validatedByChain;
	}

	protected boolean validatedByFingerprint = false;

	/***
	 * was the server ssl connection validated by fingerprint?
	 * @return true if it's an ssl connection validated by fingerprint
	 */
	public boolean isValidatedByFingerprint() {
		if (!isSecure()) {
			return false;
		}
		return validatedByFingerprint;
	}

	protected boolean validatedByHostname = false;

	/***
	 * was the server ssl connection validated by hostname match?
	 * @return true if it's an ssl connection validated by "cert's CN" == "P4Port's hostname"
	 */
	public boolean isValidatedByHostname() {
		if (!isSecure()) {
			return false;
		}
		return validatedByHostname;
	}

	protected int authFileLockTry = 0;
	protected long authFileLockDelay = 0;
	protected long authFileLockWait = 0;

	protected RpcUserAuthCounter authCounter = new RpcUserAuthCounter();

	protected IServerAddress rpcServerAddress = null;

	/**
	 * The RPC command args before the function name (i.e. "tag")
	 */
	protected Map<String, Object> cmdMapArgs = null;

	/**
	 * If true, relax the command name validation checks done in the RPC layer.
	 * This is dangerous, and any use of this should only be done if you know
	 * what you're doing and you're able to deal with the consequences (which
	 * won't be spelled out here).
	 */
	protected boolean relaxCmdNameValidationChecks = false;

	private PerformanceMonitor perfMonitor = new PerformanceMonitor();

	public String getApplicationName() {
		return applicationName;
	}

	public void setApplicationName(String applicationName) {
		this.applicationName = applicationName;
	}

	/**
	 * Get the RPC user authentication counter.
	 *
	 * @return RPC user authentication counter
	 */
	public RpcUserAuthCounter getAuthCounter() {
		return this.authCounter;
	}

	public int getClientApiLevel() {
		return clientApiLevel;
	}

	public void setClientApiLevel(int clientApiLevel) {
		this.clientApiLevel = clientApiLevel;
	}

	public PerformanceMonitor getPerfMonitor() {
		return perfMonitor;
	}

	public void setPerfMonitor(PerformanceMonitor perfMonitor) {
		this.perfMonitor = perfMonitor;
	}

	/**
	 * Get the server's address for the RPC connection.
	 *
	 * @return possibly-null RPC server address
	 */
	public IServerAddress getRpcServerAddress() {
		return rpcServerAddress;
	}

	/**
	 * Set the server's address for the RPC connection.
	 *
	 * @param rpcServerAddress RPC server address
	 */
	public void setRpcServerAddress(IServerAddress rpcServerAddress) {
		this.rpcServerAddress = rpcServerAddress;
	}

	/**
	 * Get the server's address field used for storing authentication tickets.
	 *
	 * @return - possibly null server address
	 */
	public String getServerAddress() {
		String serverAddress = this.serverAddress;
		// If id is null try to use configured server address
		if (isBlank(serverAddress)) {
			serverAddress = getServerHostPort();
		}
		return serverAddress;
	}

	/**
	 * Get the server's host and port used for the RPC connection.
	 *
	 * @return - possibly null server host and port
	 */
	public String getServerHostPort() {
		String serverHostPort = null;
		if (isNotBlank(serverHost)) {
			serverHostPort = serverHost;
			if (serverPort != UNKNOWN_SERVER_PORT) {
				serverHostPort += ":" + String.valueOf(serverPort);
			}
		} else if (serverPort != UNKNOWN_SERVER_PORT) {
			serverHostPort = String.valueOf(serverPort);
		}
		return serverHostPort;
	}

	public Charset getClientCharset() {
		if (p4Charset == null) {
			return null;
		}
		return p4Charset.getCharset();
	}

	public boolean isServerUnicode() {
		return P4Charset.isUnicodeServer(p4Charset);
	}

	/**
	 * Get the server's id field used for storing authentication tickets. This
	 * id should only be used as a server address portion for entries in a p4
	 * tickets file.
	 *
	 * @return - possibly null server id
	 */
	public String getServerId() {
		return serverId;
	}

	/**
	 * Set the server's id field used for storing authentication tickets. The id
	 * specified here will be used when saving ticket values to a p4 tickets
	 * file. This field should only be set to the server id returned as part of
	 * a server message.
	 *
	 * @param serverId serverId
	 */
	public void setServerId(String serverId) {
		this.serverId = serverId;
	}

	@Override
	public String getTicketsFilePath() {
		return ticketsFilePath;
	}

	@Override
	public void setTicketsFilePath(String ticketsFilePath) {
		Validate.notBlank(ticketsFilePath, "ticketsFilePath shouldn't null or empty");
		this.ticketsFilePath = ticketsFilePath;
	}

	@Override
	public String getTrustFilePath() {
		return this.trustFilePath;
	}

	@Override
	public void setTrustFilePath(String trustFilePath) {
		Validate.notBlank(trustFilePath, "ticketsFilePath shouldn't null or empty");
		this.trustFilePath = trustFilePath;
	}

	protected boolean isRelaxCmdNameValidationChecks() {
		return relaxCmdNameValidationChecks;
	}

	protected void setRelaxCmdNameValidationChecks(boolean relaxCmdNameValidationChecks) {
		this.relaxCmdNameValidationChecks = relaxCmdNameValidationChecks;
	}

	private boolean isClusterMember() {
		return serverInfo != null && serverInfo.getServerCluster() != null;
	}

	/**
	 * @deprecated use {@link com.perforce.p4java.impl.mapbased.server.cmd.ResultMapParser#isAuthFail(String)}
	 */
	@Deprecated
	@Override
	public boolean isAuthFail(final String errStr) {
		return ResultMapParser.isAuthFail(errStr);
	}

	/**
	 * @deprecated use {@link com.perforce.p4java.impl.mapbased.server.cmd.ResultMapParser#getInfoStr(Map)}
	 */
	@Deprecated
	@Override
	public String getInfoStr(final Map<String, Object> map) {
		return ResultMapParser.getInfoStr(map);
	}

	/**
	 * @deprecated use {@link com.perforce.p4java.impl.mapbased.server.cmd.ResultMapParser#isInfoMessage(Map)}
	 */
	@Deprecated
	public boolean isInfoMessage(final Map<String, Object> map) {
		return ResultMapParser.isInfoMessage(map);
	}

	/**
	 * @deprecated use {@link com.perforce.p4java.impl.mapbased.server.cmd.ResultMapParser#getErrorStr(Map)}
	 */
	@Deprecated
	@Override
	public String getErrorStr(final Map<String, Object> map) {
		return ResultMapParser.getErrorStr(map);
	}

	/**
	 * @deprecated use {@link com.perforce.p4java.impl.mapbased.rpc.RpcServer#setAuthTicket(String, String, String)}
	 */
	@Deprecated
	@Override
	public void setAuthTicket(final String userName, final String authTicket) {
		setAuthTicket(userName, null, authTicket);
	}

	@Override
	public void setAuthTicket(final String userName, final String serverId, final String authTicket) {

		Validate.notBlank(userName, "Null or empty userName passed to the setAuthTicket method.");
		String lowerCaseableUserName = userName;
		// Must downcase the username to find or save a ticket when
		// connected to a case insensitive server.
		if (!isCaseSensitive() && isNotBlank(userName)) {
			lowerCaseableUserName = userName.toLowerCase();
		}

		String serverAddress = serverId;
		if (isBlank(serverAddress)) {
			serverAddress = firstNonBlank(getServerId(), getServerAddress());
			// Handling 'serverCluster'
			if (isClusterMember()) {
				serverAddress = serverInfo.getServerCluster();
			}
			Validate.notBlank(serverAddress, "Null serverAddress in the setAuthTicket method.");
		}
		if (isBlank(authTicket)) {
			authTickets.remove(composeAuthTicketEntryKey(lowerCaseableUserName, serverAddress));
		} else {
			authTickets.put(composeAuthTicketEntryKey(lowerCaseableUserName, serverAddress), authTicket);
		}
	}

	@Override
	public String getTrust() throws P4JavaException {
		RpcConnection rpcConnection = null;

		try {
			rpcConnection = new RpcStreamConnection(serverHost, serverPort, props, serverStats, p4Charset, secure);

			return rpcConnection.getFingerprint();
		} finally {
			closeQuietly(rpcConnection);
		}
	}

	@Override
	public String addTrust(TrustOptions opts) throws P4JavaException {
		return addTrust(null, opts);
	}

	@Override
	public String addTrust(final String fingerprintValue) throws P4JavaException {
		Validate.notBlank(fingerprintValue, "fingerprintValue shouldn't null or empty");
		return addTrust(fingerprintValue, null);
	}

	@Override
	public String addTrust(final String fingerprintValue, final TrustOptions options) throws P4JavaException {

		RpcConnection rpcConnection = null;
		try {
			rpcConnection = new RpcStreamConnection(serverHost, serverPort, props, serverStats, p4Charset, secure);

			TrustOptions opts = ObjectUtils.firstNonNull(options, new TrustOptions());
			// Assume '-y' and '-f' options, if specified newFingerprint value.
			if (isNotBlank(fingerprintValue)) {
				opts.setAutoAccept(true);
				opts.setForce(true);
			}

			String originalFingerprint = rpcConnection.getFingerprint();

			PerforceMessages messages = clientTrust.getMessages();
			String serverHostPort = getServerHostPort();
			Object[] warningParams = {serverHostPort, originalFingerprint};
			String newConnectionWarning = messages.getMessage(CLIENT_TRUST_WARNING_NEW_CONNECTION, warningParams);
			String newKeyWarning = messages.getMessage(CLIENT_TRUST_WARNING_NEW_KEY, warningParams);
			String serverIpPort = rpcConnection.getServerIpPort();

			boolean fingerprintExists = fingerprintExists(serverIpPort, FINGERPRINT_USER_NAME);
			boolean fingerprintMatches = fingerprintMatches(serverIpPort, FINGERPRINT_USER_NAME, originalFingerprint);
			boolean fingerprintReplaceExists = fingerprintExists(serverIpPort, FINGERPRINT_REPLACEMENT_USER_NAME);
			boolean fingerprintReplaceMatches = fingerprintMatches(serverIpPort, FINGERPRINT_REPLACEMENT_USER_NAME, originalFingerprint);

			boolean fingerprintExistsHost = fingerprintExists(serverHostPort, FINGERPRINT_USER_NAME);
			boolean fingerprintMatchesHost = fingerprintMatches(serverHostPort, FINGERPRINT_USER_NAME, originalFingerprint);
			boolean fingerprintReplaceExistsHost = fingerprintExists(serverHostPort, FINGERPRINT_REPLACEMENT_USER_NAME);
			boolean fingerprintReplaceMatchesHost = fingerprintMatches(serverHostPort, FINGERPRINT_REPLACEMENT_USER_NAME, originalFingerprint);

			// auto refuse
			if (opts.isAutoRefuse()) {
				// new connection
				if (!fingerprintExists && !fingerprintExistsHost) {
					return newConnectionWarning;
				}
				// new key
				// if (!fingerprintMatches) {
				if (!fingerprintMatches && !fingerprintMatchesHost) {
					return newKeyWarning;
				}
			}

			// check and use replacement newFingerprint
			boolean established = checkAndUseReplacementFingerprint(serverIpPort, fingerprintExists, fingerprintMatches, fingerprintReplaceExists, fingerprintReplaceMatches, rpcConnection);
			boolean establishedHost = checkAndUseReplacementFingerprint(serverHostPort, fingerprintExists, fingerprintMatches, fingerprintReplaceExists, fingerprintReplaceMatches, rpcConnection);

			if (established || establishedHost) {
				return messages.getMessage(CLIENT_TRUST_ALREADY_ESTABLISHED);
			}

			String fingerprintUser = firstConditionIsTrue(opts.isReplacement(), FINGERPRINT_REPLACEMENT_USER_NAME, FINGERPRINT_USER_NAME);
			String newFingerprint = firstNonBlank(fingerprintValue, originalFingerprint);
			String trustAddedInfo = messages.getMessage(CLIENT_TRUST_ADDED, new Object[]{serverHostPort, serverIpPort});

			// new connection
			if (installFingerprintIfNewConnection(fingerprintExists, rpcConnection, opts, fingerprintUser, newFingerprint)) {

				return newConnectionWarning + trustAddedInfo;
			}

			// new key
			if (installNewFingerprintIfNewKey(fingerprintMatches, rpcConnection, opts, fingerprintUser, newFingerprint)) {

				return newKeyWarning + trustAddedInfo;
			}

			if (fingerprintMatches && isNotBlank(fingerprintValue)) {
				// install newFingerprint
				int sslClientTrustName = RpcPropertyDefs.getPropertyAsInt(this.props, RpcPropertyDefs.RPC_SECURE_CLIENT_TRUST_NAME_NICK, RpcPropertyDefs.RPC_DEFAULT_SECURE_CLIENT_TRUST_NAME);
				if (sslClientTrustName <= 1) {
					clientTrust.installFingerprint(serverIpPort, fingerprintUser, newFingerprint);
				}
				if (sslClientTrustName >= 1) {
					clientTrust.installFingerprint(getServerHostPort(), fingerprintUser, newFingerprint);
				}
				return trustAddedInfo;
			}

			// trust already established
			return messages.getMessage(CLIENT_TRUST_ALREADY_ESTABLISHED);
		} finally {
			closeQuietly(rpcConnection);
		}
	}

	@Override
	public String removeTrust() throws P4JavaException {
		return removeTrust(null);
	}

	@Override
	public String removeTrust(final TrustOptions opts) throws P4JavaException {
		RpcConnection rpcConnection = null;

		try {
			rpcConnection = new RpcStreamConnection(serverHost, serverPort, props, serverStats, p4Charset, secure);

			String fingerprintUser = firstConditionIsTrue(nonNull(opts) && opts.isReplacement(), FINGERPRINT_REPLACEMENT_USER_NAME, FINGERPRINT_USER_NAME);

			String message = EMPTY;
			PerforceMessages messages = clientTrust.getMessages();
			String fingerprint = rpcConnection.getFingerprint();
			Object[] params = {getServerHostPort(), fingerprint};
			String serverIpPort = rpcConnection.getServerIpPort();
			// new connection
			if (!fingerprintExists(serverIpPort, FINGERPRINT_USER_NAME)) {
				message = messages.getMessage(CLIENT_TRUST_WARNING_NEW_CONNECTION, params);
			} else {
				// new key
				if (!fingerprintMatches(serverIpPort, FINGERPRINT_USER_NAME, fingerprint)) {
					message = messages.getMessage(CLIENT_TRUST_WARNING_NEW_KEY, params);
				}
			}

			// remove the fingerprint from the trust file
			if (fingerprintExists(serverIpPort, fingerprintUser)) {
				clientTrust.removeFingerprint(serverIpPort, fingerprintUser);
				message += messages.getMessage(CLIENT_TRUST_REMOVED, new Object[]{getServerHostPort(), serverIpPort});
			}
			// remove the host entry
			if (fingerprintExists(getServerHostPort(), fingerprintUser)) {
				clientTrust.removeFingerprint(getServerHostPort(), fingerprintUser);
				message += (message.length() > 0 ? " " : "") + messages.getMessage(CLIENT_TRUST_REMOVED, new Object[]{getServerHostPort(), getServerHostPort()});
			}
			return message;
		} finally {
			closeQuietly(rpcConnection);
		}
	}

	@Override
	public List<Fingerprint> getTrusts() throws P4JavaException {
		return getTrusts(null);
	}

	@Override
	public List<Fingerprint> getTrusts(final TrustOptions opts) throws P4JavaException {
		Fingerprint[] fingerprints = loadFingerprints();
		if (nonNull(fingerprints)) {
			List<Fingerprint> fingerprintsList = new ArrayList<>();
			List<Fingerprint> replacementsList = new ArrayList<>();
			for (Fingerprint fingerprint : fingerprints) {
				if (nonNull(fingerprint) && isNotBlank(fingerprint.getUserName())) {
					if (FINGERPRINT_REPLACEMENT_USER_NAME.equalsIgnoreCase(fingerprint.getUserName())) {
						replacementsList.add(fingerprint);
					} else {
						fingerprintsList.add(fingerprint);
					}
				}
			}
			if (nonNull(opts) && opts.isReplacement()) {
				return replacementsList;
			} else {
				return fingerprintsList;
			}
		}
		return Collections.emptyList();
	}

	/**
	 * Try to establish an actual RPC connection to the target Perforce server.
	 * Most of the actual setup work is done in the RpcConnection and
	 * RpcPacketDispatcher constructors, but associated gubbins such as auto
	 * login, etc., are done in the superclass.
	 */
	@Override
	public void connect() throws ConnectionException, AccessException, RequestException, ConfigException {
		connectionStart = System.currentTimeMillis();
		super.connect();
	}

	/**
	 * Try to cleanly disconnect from the Perforce server at the other end of
	 * the current connection (with the emphasis on "cleanly"). This should
	 * theoretically include sending a release2 message, but we don't always get
	 * the chance to do that.
	 */
	@Override
	public void disconnect() throws ConnectionException, AccessException {
		super.disconnect();

		if (connectionStart != 0) {
			Log.stats("RPC connection connected for %s msec elapsed time", (System.currentTimeMillis() - connectionStart));
		}
		serverStats.logStats();

		// Clear up all counts for this RPC server
		authCounter.clearCount();
	}

	/**
	 * @deprecated use {@link com.perforce.p4java.impl.mapbased.rpc.RpcServer#getAuthTicket(String, String)}
	 */
	@Deprecated
	@Override
	public String getAuthTicket(final String userName) {
		return getAuthTicket(userName, null);
	}

	@Override
	public String getAuthTicket(final String userName, final String serverId) {
		if (isBlank(userName) || authTickets.isEmpty()) {
			return null;
		}
		// Must downcase the username to find or save a ticket when
		// connected to a case insensitive server.
		String lowerCaseableUserName = userName;
		if (!isCaseSensitive() && isNotBlank(userName)) {
			lowerCaseableUserName = userName.toLowerCase();
		}
		String serverAddress = serverId;
		if (isBlank(serverAddress)) {
			serverAddress = firstNonBlank(getServerId(), getServerAddress());

			if (!authTickets.containsKey(composeAuthTicketEntryKey(lowerCaseableUserName, serverAddress))) {
				serverAddress = getServerAddress();
			}

			// Handling 'serverCluster'
			if (isClusterMember()) {
				serverAddress = serverInfo.getServerCluster();
			}
		}
		if (isNotBlank(lowerCaseableUserName) && isNotBlank(serverAddress)) {
			return authTickets.get(composeAuthTicketEntryKey(lowerCaseableUserName, serverAddress));
		}
		return null;
	}

	@Override
	public boolean isLoginNotRequired(String msgStr) {
		return contains(msgStr, PASSWORD_NOT_SET_STRING);
	}

	@Override
	public boolean supportsSmartMove() throws ConnectionException, RequestException, AccessException {
		// Return true iff server version >= 2009.1
		// and move command is not disabled on server
		if (serverVersion < 20091) {
			return false;
		}

		IServerInfo info = getServerInfo();
		return nonNull(info) && !info.isMoveDisabled();
	}

	public ServerStatus init(final String host, final int port, final Properties properties, final UsageOptions opts, final boolean secure) throws ConfigException, ConnectionException {

		super.init(host, port, properties, opts, secure);
		try {
			cmdMapArgs = new HashMap<>();
			cmdMapArgs.put(ProtocolCommand.RPC_ARGNAME_PROTOCOL_ZTAGS, EMPTY);
			relaxCmdNameValidationChecks = getPropertyAsBoolean(properties, RPC_RELAX_CMD_NAME_CHECKS_NICK, false);
			applicationName = RpcPropertyDefs.getProperty(properties, RPC_APPLICATION_NAME_NICK);
			if (isNotBlank(getUsageOptions().getHostName())) {
				localHostName = getUsageOptions().getHostName();
			} else {
				localHostName = InetAddress.getLocalHost().getHostName();
			}

			Validate.notBlank(localHostName, "Null or empty client host name in RPC connection init");

			if (!useAuthMemoryStore) {
				// Search properties for ticket file path, fix for job035376
				ticketsFilePath = getPropertyByKeys(props, TICKET_PATH_KEY_SHORT_FORM, TICKET_PATH_KEY);
				// Search environment variable
				if (isBlank(ticketsFilePath)) {
					ticketsFilePath = PerforceEnvironment.getP4Tickets();
				}
				// Search standard OS location
				if (isBlank(ticketsFilePath)) {
					ticketsFilePath = getDefaultP4TicketsFile();
				}

				// Search properties for trust file path
				trustFilePath = getPropertyByKeys(props, TRUST_PATH_KEY_SHORT_FORM, TRUST_PATH_KEY);
				// Search environment variable
				if (isBlank(trustFilePath)) {
					trustFilePath = PerforceEnvironment.getP4Trust();
				}
				// Search standard OS location
				if (isBlank(trustFilePath)) {
					trustFilePath = getDefaultP4TrustFile();
				}
			}
			serverStats = new ServerStats();
			// Auth file lock handling properties
			authFileLockTry = getPropertyAsInt(properties, new String[]{AUTH_FILE_LOCK_TRY_KEY_SHORT_FORM, AUTH_FILE_LOCK_TRY_KEY}, AbstractAuthHelper.DEFAULT_LOCK_TRY);

			authFileLockDelay = getPropertyAsLong(properties, new String[]{AUTH_FILE_LOCK_DELAY_KEY_SHORT_FORM, AUTH_FILE_LOCK_DELAY_KEY}, AbstractAuthHelper.DEFAULT_LOCK_DELAY);

			authFileLockWait = getPropertyAsLong(properties, new String[]{AUTH_FILE_LOCK_WAIT_KEY_SHORT_FORM, AUTH_FILE_LOCK_WAIT_KEY}, AbstractAuthHelper.DEFAULT_LOCK_WAIT);
		} catch (UnknownHostException uhe) {
			throw new ConfigException("Unable to determine client host name: %s" + uhe.getLocalizedMessage());
		}

		// Initialize client trust
		clientTrust = new ClientTrust(this);

		return status;
	}

	@Override
	public ServerStatus init(final String host, final int port, final Properties props, final UsageOptions opts) throws ConfigException, ConnectionException {

		return init(host, port, props, opts, false);
	}

	/**
	 * The default init sets up things like host names, etc., and fails if we
	 * can't establish some pretty basic things at connect time. Does <i>not</i>
	 * attempt to actually connect to the target Perforce server -- this is left
	 * for the connect() call, below.
	 */
	@Override
	public ServerStatus init(final String host, final int port, final Properties props) throws ConfigException, ConnectionException {

		return init(host, port, props, null);
	}

	private boolean checkAndUseReplacementFingerprint(final String serverKey, final boolean fingerprintExists, final boolean fingerprintMatches, final boolean fingerprintReplaceExists, final boolean fingerprintReplaceMatches, final RpcConnection rpcConnection) throws TrustException {

		if ((!fingerprintExists || !fingerprintMatches) && (fingerprintReplaceExists && fingerprintReplaceMatches)) {
			// Install/override newFingerprint
			clientTrust.installFingerprint(serverKey, FINGERPRINT_USER_NAME, rpcConnection.getFingerprint());
			// Remove the replacement
			clientTrust.removeFingerprint(serverKey, FINGERPRINT_REPLACEMENT_USER_NAME);

			return true;
		}
		return false;
	}

	/**
	 * Check Server Trust
	 * <p>
	 * Certificate Validation depends on RPC_SSL_CLIENT_CERT_VALIDATE_NICK.
	 * <p>
	 * Self-signed certs use only a fingerprint comparison after checking the cert's dates.
	 *
	 * @param rpcConnection rpcConnection
	 * @throws ConnectionException on error
	 */
	public void trustConnectionCheck(final RpcConnection rpcConnection) throws ConnectionException {

		int sslCertMethod = RpcPropertyDefs.getPropertyAsInt(this.props, RpcPropertyDefs.RPC_SECURE_CLIENT_CERT_VALIDATE_NICK, RpcPropertyDefs.RPC_DEFAULT_SECURE_CLIENT_CERT_VALIDATE);
		// 0 = fingerprint, 1=chain then fingerprint, 2=hostname check only.
		if (sslCertMethod < 0 || sslCertMethod > 2) {
			sslCertMethod = RpcPropertyDefs.RPC_DEFAULT_SECURE_CLIENT_CERT_VALIDATE;
		}
		if (!rpcConnection.isSelfSigned() && rpcConnection.getServerCerts().length >= 2 && sslCertMethod == 1) {
			// validate the chain.
			try {
				ClientTrust.validateServerChain(rpcConnection.getServerCerts(), getServerHostPort().split(":")[0]);
				validatedByChain = true;

				return;
			} catch (Exception ce) {
				boolean x = ce instanceof CertificateException;
				// TODO: logging? - failure to validate cert chain so fallback to fingerprint.
				// System.out.println("Fails cert chain: " + ce.getMessage());
			}
		} else if (!rpcConnection.isSelfSigned() && rpcConnection.getServerCerts().length >= 2 && sslCertMethod == 2) {
			// validate the P4PORT matches the CN.
			X509Certificate[] certs = rpcConnection.getServerCerts();
			try {
				ClientTrust.verifyCertificateSubject(certs[0], this.serverHost);
				validatedByHostname = true;
				return;
			} catch (Exception e) {
				// TODO:  logging - requested a subject match verification but didn't match.
			}
		}

		checkFingerprint(rpcConnection);
		validatedByFingerprint = rpcConnection.isTrusted();
	}

	/**
	 * Check the fingerprint of the Perforce server SSL connection
	 *
	 * @param rpcConnection rpcConnection
	 * @throws ConnectionException on error
	 */
	protected void checkFingerprint(final RpcConnection rpcConnection) throws ConnectionException {

		if (nonNull(rpcConnection) && rpcConnection.isSecure() && !rpcConnection.isTrusted()) {

			try {
				if (rpcConnection.getServerCerts().length > 0) {
					ClientTrust.verifyCertificateDates(rpcConnection.getServerCerts()[0]);
				}
			} catch (CertificateException e) {
				throw new ConnectionException(e);
			}

			String fingerprint = rpcConnection.getFingerprint();
			throwConnectionExceptionIfConditionFails(isNotBlank(fingerprint), "Null fingerprint for this Perforce SSL connection");

			// look for IP in trust file
			String serverIpPort = rpcConnection.getServerIpPort();
			boolean fingerprintExists = fingerprintExists(serverIpPort, FINGERPRINT_USER_NAME);
			boolean fingerprintReplaceExist = fingerprintExists(serverIpPort, FINGERPRINT_REPLACEMENT_USER_NAME);

			boolean fingerprintMatches = clientTrust.fingerprintMatches(serverIpPort, FINGERPRINT_USER_NAME, fingerprint);
			boolean fingerprintReplaceMatches = fingerprintMatches(serverIpPort, FINGERPRINT_REPLACEMENT_USER_NAME, fingerprint);

			boolean isNotEstablished = (!fingerprintExists && !fingerprintReplaceExist) || (!fingerprintExists && !fingerprintReplaceMatches);

			// look for host:port in trust file
			String serverHost = getServerHostPort();
			boolean fingerprintExistsHost = fingerprintExists(serverHost, FINGERPRINT_USER_NAME);
			boolean fingerprintReplaceExistHost = fingerprintExists(serverHost, FINGERPRINT_REPLACEMENT_USER_NAME);

			boolean fingerprintMatchesHost = clientTrust.fingerprintMatches(serverHost, FINGERPRINT_USER_NAME, fingerprint);
			boolean fingerprintReplaceMatchesHost = fingerprintMatches(serverHost, FINGERPRINT_REPLACEMENT_USER_NAME, fingerprint);

			boolean isNotEstablishedHost = (!fingerprintExistsHost && !fingerprintReplaceExistHost) || (!fingerprintExistsHost && !fingerprintReplaceMatchesHost);

			// first check:  is either IP or host found?
			throwTrustExceptionIfConditionIsTrue(isNotEstablished && isNotEstablishedHost, rpcConnection, NEW_CONNECTION, CLIENT_TRUST_WARNING_NOT_ESTABLISHED, CLIENT_TRUST_EXCEPTION_NEW_CONNECTION);

			boolean isNewKey = (!fingerprintMatches && !fingerprintReplaceMatches);
			boolean isNewKeyHost = (!fingerprintMatchesHost && !fingerprintReplaceMatchesHost);

			throwTrustExceptionIfConditionIsTrue(isNewKey && isNewKeyHost, rpcConnection, NEW_KEY, CLIENT_TRUST_WARNING_NEW_KEY, CLIENT_TRUST_EXCEPTION_NEW_KEY);

			// Use replacement fingerprint for serverIP
			if ((!fingerprintExists || !fingerprintMatches) && (fingerprintReplaceExist && fingerprintReplaceMatches)) {
				// Install/override fingerprint
				clientTrust.installFingerprint(serverIpPort, FINGERPRINT_USER_NAME, fingerprint);
				// Remove the replacement
				clientTrust.removeFingerprint(serverIpPort, FINGERPRINT_REPLACEMENT_USER_NAME);
			}
			// Use replacement fingerprint for hostname
			if ((!fingerprintExistsHost || !fingerprintMatchesHost) && (fingerprintReplaceExistHost && fingerprintReplaceMatchesHost)) {
				// Install/override fingerprint
				clientTrust.installFingerprint(serverHost, FINGERPRINT_USER_NAME, fingerprint);
				// Remove the replacement
				clientTrust.removeFingerprint(serverHost, FINGERPRINT_REPLACEMENT_USER_NAME);
			}

			// Trust this connection
			rpcConnection.setTrusted(true);
		}
	}

	private boolean fingerprintExists(final String serverIpPort, final String fingerprintUser) {
		return clientTrust.fingerprintExists(serverIpPort, fingerprintUser);
	}

	private boolean fingerprintMatches(final String serverIpPort, final String fingerprintUser, final String fingerprint) {

		return clientTrust.fingerprintMatches(serverIpPort, fingerprintUser, fingerprint);
	}

	private void throwTrustExceptionIfConditionIsTrue(final boolean expression, final RpcConnection rpcConnection, final TrustException.Type type, final String warningMessageKey, final String exceptionMessageKey) throws TrustException {
		if (expression) {
			throwTrustException(rpcConnection, type, warningMessageKey, exceptionMessageKey);
		}
	}

	private void closeQuietly(@Nullable final RpcConnection rpcConnection) throws ConnectionException {
		if (nonNull(rpcConnection)) {
			rpcConnection.disconnect(null);
		}
	}

	/**
	 * Compose the key for an auth ticket entry
	 *
	 * @param userName      userName
	 * @param serverAddress serverAddress
	 * @return key
	 */
	protected String composeAuthTicketEntryKey(final String userName, final String serverAddress) {

		Validate.notBlank(userName, "Null userName passed to the composeAuthTicketEntryKey method.");
		Validate.notBlank(serverAddress, "Null serverAddress passed to the composeAuthTicketEntryKey method.");

		String wellFormedServerAddress = serverAddress;

		if (indexOf(serverAddress, ':') == -1) {
			wellFormedServerAddress = "localhost:" + serverAddress;
		}
		return (wellFormedServerAddress + "=" + userName);
	}

	protected String getClientNameForEnv() {
		if (isNotBlank(clientName)) {
			return clientName;
		} else {
			return getUsageOptions().getUnsetClientName();
		}
	}

	protected String getHostForEnv() {
		if (isNotBlank(localHostName)) {
			return localHostName;
		}
		return RPC_ENV_NOHOST_SPEC;
	}

	protected String getLanguageForEnv() {
		return getUsageOptions().getTextLanguage();
	}

	protected String getOsTypeForEnv() {
		String osName = System.getProperty(RPC_ENV_OS_NAME_KEY);

		if (isNotBlank(osName) && osName.toLowerCase(Locale.ENGLISH).contains(RPC_ENV_WINDOWS_PREFIX)) {
			return RPC_ENV_WINDOWS_SPEC;
		}

		return RPC_ENV_UNIX_SPEC; // sic -- as seen in the C++ API...
	}

	/**
	 * Get the RPC packet field rule for skipping the charset conversion of a
	 * range of RPC packet fields; leave the values as bytes.
	 * <p>
	 * Note: currently only supporting the "export" command.
	 *
	 * @param inMap   inMap
	 * @param cmdSpec cmdSpec
	 * @return RpcPacketFieldRule
	 */
	protected RpcPacketFieldRule getRpcPacketFieldRule(final Map<String, Object> inMap, final CmdSpec cmdSpec) {

		if (nonNull(inMap) && nonNull(cmdSpec)) {
			if (cmdSpec == CmdSpec.EXPORT && inMap.containsKey(cmdSpec.toString()) && (inMap.get(cmdSpec.toString()) instanceof Map<?, ?>)) {
				@SuppressWarnings("unchecked") Map<String, Object> cmdMap = (Map<String, Object>) inMap.remove(cmdSpec.toString());
				if (nonNull(cmdMap)) {
					return RpcPacketFieldRule.getInstance(cmdMap);
				}
			}
		}
		return null;
	}

	public String getSecretKey() {
		return getSecretKey(userName);
	}

	public void setSecretKey(String secretKey) {
		setSecretKey(userName, secretKey);
	}

	public String getSecretKey(String userName) {
		if (isNotBlank(userName)) {
			return secretKeys.get(userName);
		}
		return null;
	}

	public String getPBuf(String userName) {
		if (isNotBlank(userName)) {
			return pBufs.get(userName);
		}
		return null;
	}

	protected String getUserForEnv() {
		if (isNotBlank(userName)) {
			return userName;
		}
		return getUsageOptions().getUnsetUserName();
	}

	private boolean installFingerprintIfNewConnection(final boolean fingerprintExists, final RpcConnection rpcConnection, final TrustOptions trustOptions, final String fingerprintUser, final String newFingerprint) throws TrustException {

		if (!fingerprintExists) {
			if (installNewFingerprintIfIsAutoAccept(rpcConnection, trustOptions, fingerprintUser, newFingerprint)) {
				return true;
			}

			// didn't accept
			throwTrustException(rpcConnection, NEW_CONNECTION, CLIENT_TRUST_WARNING_NEW_CONNECTION, CLIENT_TRUST_ADD_EXCEPTION_NEW_CONNECTION);
		}
		return false;
	}

	private boolean installNewFingerprintIfNewKey(final boolean fingerprintMatches, final RpcConnection rpcConnection, final TrustOptions trustOptions, final String fingerprintUser, final String newFingerprint) throws TrustException {

		if (!fingerprintMatches) {
			// force install
			if (trustOptions.isForce()) {
				if (installNewFingerprintIfIsAutoAccept(rpcConnection, trustOptions, fingerprintUser, newFingerprint)) {
					return true;
				}
			}
			// didn't accept / not force install
			throwTrustException(rpcConnection, NEW_KEY, CLIENT_TRUST_WARNING_NEW_KEY, CLIENT_TRUST_ADD_EXCEPTION_NEW_KEY);
		}
		return false;
	}

	private boolean installNewFingerprintIfIsAutoAccept(final RpcConnection rpcConnection, final TrustOptions trustOptions, final String fingerprintUser, final String newFingerprint) throws TrustException {

		if (trustOptions.isAutoAccept()) {
			int sslClientTrustName = RpcPropertyDefs.getPropertyAsInt(this.props, RpcPropertyDefs.RPC_SECURE_CLIENT_TRUST_NAME_NICK, RpcPropertyDefs.RPC_DEFAULT_SECURE_CLIENT_TRUST_NAME);
			// install newFingerprint
			if (sslClientTrustName == 0 || sslClientTrustName == 1) {
				clientTrust.installFingerprint(rpcConnection.getServerIpPort(), fingerprintUser, newFingerprint);
			}
			if (sslClientTrustName == 1 || sslClientTrustName == 2) {
				String serverHostNamePort = rpcConnection.getServerHostNamePort();
				if (serverHostNamePort != null) {
					clientTrust.installFingerprint(serverHostNamePort, fingerprintUser, newFingerprint);
				}
			}
			return true;
		}

		return false;
	}

	private void throwTrustException(final RpcConnection rpcConnection, final TrustException.Type type, final String warningMessageKey, final String exceptionMessageKey) throws TrustException {

		Object[] warningParams = {getServerHostPort(), rpcConnection.getFingerprint()};
		String warningMessage = clientTrust.getMessages().getMessage(warningMessageKey, warningParams);
		String exceptionMessage = clientTrust.getMessages().getMessage(exceptionMessageKey);
		throw new TrustException(type, getServerHostPort(), rpcConnection.getServerIpPort(), rpcConnection.getFingerprint(), warningMessage + exceptionMessage);
	}

	/**
	 * Get the p4trust entry value for the server IP and port based upon a
	 * search of either the file found at
	 * {@link PropertyDefs#TRUST_PATH_KEY_SHORT_FORM},
	 * {@link PropertyDefs#TRUST_PATH_KEY}, the P4TRUST environment variable or
	 * the standard p4trust file location for the current OS. Will return null
	 * if not found or if an error occurred attempt to lookup the value.
	 *
	 * @param serverKey       serverKey
	 * @param fingerprintUser fingerprintUser
	 * @return - fingerprint or null if not found.
	 */
	public Fingerprint loadFingerprint(final String serverKey, final String fingerprintUser) {

		if (isBlank(serverKey) || isBlank(fingerprintUser)) {
			return null;
		}

		Fingerprint fingerprint = null;
		try {
			fingerprint = FingerprintsHelper.getFingerprint(fingerprintUser, serverKey, trustFilePath);
		} catch (IOException e) {
			Log.error(e.getMessage());
		}

		return fingerprint;
	}

	/**
	 * Get the p4trust entries from the file found at
	 * {@link PropertyDefs#TRUST_PATH_KEY_SHORT_FORM},
	 * {@link PropertyDefs#TRUST_PATH_KEY}, the P4TRUST environment variable or
	 * the standard p4trust file location for the current OS. Will return null
	 * if nothing found or if an error occurred attempt to lookup the entries.
	 *
	 * @return - list of fingerprints or null if nothing found.
	 */
	public Fingerprint[] loadFingerprints() {
		Fingerprint[] fingerprints = null;
		try {
			fingerprints = FingerprintsHelper.getFingerprints(trustFilePath);
		} catch (IOException e) {
			Log.error(e.getMessage());
		}

		return fingerprints;
	}

	/**
	 * Get the p4tickets entry value for the current user returned from
	 * {@link #getUserName()} and server address based upon a search of either
	 * the file found at {@link PropertyDefs#TICKET_PATH_KEY_SHORT_FORM},
	 * {@link PropertyDefs#TICKET_PATH_KEY}, the P4TICKETS environment variable
	 * or the standard p4tickets file location for the current OS. Will return
	 * null if not found or if an error occurred attempt to lookup the value.
	 *
	 * @param serverId serverId
	 * @return - ticket value to get used for {@link #setAuthTicket(String)} or
	 * null if not found.
	 */
	@Nullable
	public String loadTicket(final String serverId) {
		return loadTicket(serverId, getUserName());
	}

	/**
	 * Get the p4tickets entry value for the specified user and server address
	 * based upon a search of either the file found at
	 * {@link PropertyDefs#TICKET_PATH_KEY_SHORT_FORM},
	 * {@link PropertyDefs#TICKET_PATH_KEY}, the P4TICKETS environment variable
	 * or the standard p4tickets file location for the current OS. Will return
	 * null if not found or if an error occurred attempt to lookup the value.
	 *
	 * @param serverId serverId
	 * @param name     name
	 * @return - ticket value to get used for {@link #setAuthTicket(String)} or
	 * null if not found.
	 */
	public String loadTicket(String serverId, String name) {
		String ticketValue = null;
		if (isNotBlank(name)) {
			ticketValue = quietGetTicketValue(name, serverId);
			if (isBlank(ticketValue)) {
				String server = getServerHostPort();
				ticketValue = quietGetTicketValue(name, server);
			}
		}
		return ticketValue;
	}

	@Nullable
	private String quietGetTicketValue(final String userName, final String serverOrServerId) {
		String ticketValue = null;
		try {
			ticketValue = AuthTicketsHelper.getTicketValue(userName, serverOrServerId, ticketsFilePath);
		} catch (IOException ignore) {
		}

		return ticketValue;
	}

	protected void processCmdCallbacks(final int cmdCallBackKey, final long timeTaken, final List<Map<String, Object>> resultMaps) {

		commandCallback.completedServerCommand(cmdCallBackKey, timeTaken);
		if (nonNull(resultMaps)) {
			for (Map<String, Object> map : resultMaps) {
				String str = ResultMapParser.getErrorOrInfoStr(map);
				if (isNotBlank(str)) {
					str = str.trim();
				}
				int severity = getSeverityCode(map);
				int generic = getGenericCode(map);
				if (severity != E_EMPTY) {
					commandCallback.receivedServerMessage(cmdCallBackKey, generic, severity, str);
				}

				if (severity == E_INFO) {
					commandCallback.receivedServerInfoLine(cmdCallBackKey, str);
				} else if (severity >= E_FAILED) {
					commandCallback.receivedServerErrorLine(cmdCallBackKey, str);
				}
			}
		}
	}

	/**
	 * @deprecated use {@link com.perforce.p4java.impl.mapbased.server.cmd.ResultMapParser#getErrorOrInfoStr(Map)}
	 */
	@Deprecated
	@Override
	public String getErrorOrInfoStr(Map<String, Object> map) {
		return ResultMapParser.getErrorOrInfoStr(map);
	}

	public int getSeverityCode(Map<String, Object> map) {
		// Note: only gets first severity, i.e. code0:
		if (nonNull(map) && map.containsKey("code0")) {
			return getSeverity(parseCode0ErrorString(map));
		}

		return E_EMPTY;
	}

	public int getGenericCode(Map<String, Object> map) {
		// Note: only gets first code, i.e. code0:
		if (nonNull(map) && map.containsKey("code0")) {
			return getGeneric(parseCode0ErrorString(map));
		}

		return EV_NONE;
	}

	/**
	 * Return the Perforce Server's authId.
	 * <p>
	 * This may be: addr:port or clusterId or authId If the connection hasn't
	 * been made yet, this could be null.
	 *
	 * @return possibly-null Perforce authentication id
	 * @since 2016.1
	 */
	public String getAuthId() {
		if (isClusterMember()) {
			return serverInfo.getServerCluster();
		}

		return getServerHostPort();
	}

	/**
	 * Save current ticket returned from {@link #getAuthTicket()}.
	 *
	 * @throws P4JavaException on error
	 * @deprecated use {@link com.perforce.p4java.impl.mapbased.rpc.RpcServer#saveTicket(String, String, String)}
	 */
	@Deprecated
	public void saveCurrentTicket() throws P4JavaException {
		saveTicket(getAuthTicket());
	}

	/**
	 * Save specified auth ticket value as associate with this server's address
	 * and configured user returned from {@link #getUserName()}. This will
	 * attempt to write an entry to the p4tickets file either specified as the
	 * P4TICKETS environment variable or at the OS specific default location. If
	 * the ticket value is null then the current entry in the will be cleared.
	 *
	 * @param ticketValue ticketValue
	 * @throws ConfigException on error
	 * @deprecated use {@link com.perforce.p4java.impl.mapbased.rpc.RpcServer#saveTicket(String, String, String)}
	 */
	@Deprecated
	public void saveTicket(String ticketValue) throws ConfigException {
		saveTicket(getUserName(), null, ticketValue);
	}

	/**
	 * Save specified fingerprint value as associate with this server's address.
	 * This will attempt to write an entry to the p4trust file either specified
	 * as the P4TRUST environment variable or at the OS specific default
	 * location. If the fingerprint value is null then the current entry will be
	 * cleared.
	 *
	 * @param serverIpPort     serverIpPort
	 * @param fingerprintUser  fingerprintUser
	 * @param fingerprintValue fingerprintValue
	 * @throws ConfigException on error
	 */
	public void saveFingerprint(final String serverIpPort, final String fingerprintUser, final String fingerprintValue) throws ConfigException {

		if (isBlank(serverIpPort) || isBlank(fingerprintUser)) {
			return;
		}

		// Save the fingerprint by server IP and port
		try {
			FingerprintsHelper.saveFingerprint(fingerprintUser, serverIpPort, fingerprintValue, trustFilePath, authFileLockTry, authFileLockDelay, authFileLockWait);
		} catch (IOException e) {
			throw new ConfigException(e);
		}
	}

	/**
	 * Save specified auth ticket value as associate with this server's address
	 * and user name from the userName parameter. This will attempt to write an
	 * entry to the p4tickets file either specified as the P4TICKETS environment
	 * variable or at the OS specific default location. If the ticket value is
	 * null then the current entry will be cleared.
	 *
	 * @param userName    userName
	 * @param serverId    serverId
	 * @param ticketValue ticketValue
	 * @throws ConfigException on error
	 */
	public void saveTicket(final String userName, final String serverId, final String ticketValue) throws ConfigException {
		String lowerCaseableUserName = getLowerCaseableUserName(userName);
		String serId = isNotBlank(serverId) ? serverId : getServerId();
		// Try to save the ticket by server id first if set
		ConfigException exception = quietSaveTicket(serId, lowerCaseableUserName, ticketValue, null);

		// If id is null try to use configured server address
		// If ticket value is null try to clear out any old values by the
		// configured server address
		if (isBlank(ticketValue) || isBlank(serId)) {
			// Try to save the ticket by server address
			String server = getServerHostPort();
			exception = quietSaveTicket(server, lowerCaseableUserName, ticketValue, exception);
		}

		// Throw the exception from either save attempt
		if (nonNull(exception)) {
			throw exception;
		}
	}

	/**
	 * Must downcase the username to find or save a ticket when connected to a
	 * case insensitive server.
	 *
	 * @param userName userName
	 * @return username
	 */
	private String getLowerCaseableUserName(final String userName) {
		String lowerCaseableUserName = userName;
		if (!isCaseSensitive() && isNotBlank(userName)) {
			lowerCaseableUserName = userName.toLowerCase();
		}

		return lowerCaseableUserName;
	}

	@Nullable
	private ConfigException quietSaveTicket(final String serverOrServerId, final String lowerCaseUserName, final String ticketValue, @Nullable final ConfigException exception) {

		if (isNotBlank(serverOrServerId)) {
			try {
				AuthTicketsHelper.saveTicket(lowerCaseUserName, serverOrServerId, ticketValue, ticketsFilePath, authFileLockTry, authFileLockDelay, authFileLockWait);
			} catch (IOException e) {
				if (nonNull(exception)) {
					exception.addSuppressed(e);
					return exception;
				} else {
					return new ConfigException(e);
				}
			}
		}

		return null;
	}

	public void setSecretKey(final String userName, final String secretKey) {
		if (isNotBlank(userName)) {
			if (isBlank(secretKey)) {
				secretKeys.remove(userName);
			} else {
				secretKeys.put(userName, secretKey);
			}
		}
	}

	public void setPbuf(final String userName, final String pBuf) {
		if (isNotBlank(userName)) {
			if (isBlank(pBuf)) {
				pBufs.remove(userName);
			} else {
				pBufs.put(userName, pBuf);
			}
		}
	}

	/**
	 * Allow for per-command use of tags or not. Currently has limited use (only
	 * a few commands are anomalous as far as we can tell), but may find more
	 * uses generally with experience.
	 * <p>
	 * This is normally used on a per-command (OneShot RPC server) basis. In
	 * order to use this on a per-session (NTS RPC server) implementation you
	 * must resend the RPC protocol, if the 'useTags' state has changed, prior
	 * to sending the command.
	 *
	 * @param cmdName     cmdName
	 * @param cmdArgs     cmdArgs
	 * @param inMap       inMap
	 * @param isStreamCmd isStreamCmd
	 * @return RPC_TAGS_USED | false
	 */
	protected boolean useTags(String cmdName, String[] cmdArgs, Map<String, Object> inMap, boolean isStreamCmd) {

		CmdSpec cmdSpec = getValidP4JCmdSpec(cmdName);
		if (nonNull(cmdSpec)) {
			if (cmdSpec == LOGIN || cmdSpec == LOGIN2) {
				return false;
			}
			if (isStreamCmd) {
				switch (cmdSpec) {
					case DESCRIBE:
					case DIFF2:
					case PRINT:
					case PROTECT:
						return false;
					default:
						break;
				}
			}
			// Check the inMap for any tagged/non-tagged override
			if (nonNull(inMap)) {
				if (inMap.containsKey(IN_MAP_USE_TAGS_KEY)) {
					return Boolean.valueOf((String) inMap.remove(IN_MAP_USE_TAGS_KEY));
				}
			}
		}
		return RPC_TAGS_USED;
	}

	/**
	 * Return true if we should be performing server -&gt; client file write I/O
	 * operations in place for this command.
	 * <p>
	 * See PropertyDefs.WRITE_IN_PLACE_KEY javadoc for the semantics of this.
	 *
	 * @param cmdName non-null command command name string
	 * @return true iff we should do a sync in place
	 */
	protected boolean writeInPlace(String cmdName) {
		String writeInPlaceKeyPropertyValue = System.getProperty(WRITE_IN_PLACE_KEY, props.getProperty(WRITE_IN_PLACE_SHORT_FORM, "false"));

		return cmdName.equalsIgnoreCase(CmdSpec.SYNC.toString()) && Boolean.valueOf(writeInPlaceKeyPropertyValue);
	}
}
