/**
 *
 */
package com.perforce.p4java.impl.generic.client;

import com.perforce.p4java.client.IClientSummary.IClientOptions;

/**
 * Simple default generic IClientOptions implementation class.
 */

public class ClientOptions implements IClientOptions {

	private boolean allWrite = false;
	private boolean clobber = false;
	private boolean compress = false;
	private boolean locked = false;
	private boolean modtime = false;
	private boolean rmdir = false;
	private boolean altsync = false;
	private boolean altSyncFeatureEnabled = false;

	/**
	 * Default constructor; sets all fields to false.
	 */
	public ClientOptions() {
	}

	/**
	 * Explicit-value constructor.
	 *
	 * @param allWrite allWrite
	 * @param clobber  clobber
	 * @param compress compress
	 * @param locked   locked
	 * @param modtime  modtime
	 * @param rmdir    rmdir
	 */
	public ClientOptions(boolean allWrite, boolean clobber, boolean compress, boolean locked, boolean modtime, boolean rmdir) {
		this.allWrite = allWrite;
		this.clobber = clobber;
		this.compress = compress;
		this.locked = locked;
		this.modtime = modtime;
		this.rmdir = rmdir;
	}

	/**
	 * Explicit-value constructor.
	 *
	 * @param allWrite allWrite
	 * @param clobber  clobber
	 * @param compress compress
	 * @param locked   locked
	 * @param modtime  modtime
	 * @param rmdir    rmdir
	 * @param altsync  altsync
	 */
	public ClientOptions(boolean allWrite, boolean clobber, boolean compress, boolean locked, boolean modtime, boolean rmdir, boolean altsync) {
		this.allWrite = allWrite;
		this.clobber = clobber;
		this.compress = compress;
		this.locked = locked;
		this.modtime = modtime;
		this.rmdir = rmdir;
		this.altsync = altsync;

		this.altSyncFeatureEnabled = true;
	}

	/**
	 * Attempts to construct a ClientOptions object from a typical p4 cmd options string,
	 * e.g. "noallwrite noclobber nocompress unlocked nomodtime normdir noaltsync". If optionsString
	 * is null, this is equivalent to calling the default constructor.
	 *
	 * @param optionsString options
	 */
	public ClientOptions(String optionsString) {

		if (optionsString != null) {
			String opts[] = optionsString.split(" ");

			this.altSyncFeatureEnabled = isAltSyncFeatureEnabled(opts);

			for (String str : opts) {
				if (str.equalsIgnoreCase("allwrite")) {
					this.allWrite = true;
				} else if (str.equalsIgnoreCase("clobber")) {
					this.clobber = true;
				} else if (str.equalsIgnoreCase("compress")) {
					this.compress = true;
				} else if (str.equalsIgnoreCase("locked")) {
					this.locked = true;
				} else if (str.equalsIgnoreCase("modtime")) {
					this.modtime = true;
				} else if (str.equalsIgnoreCase("rmdir")) {
					this.rmdir = true;
				} else if (str.equalsIgnoreCase("altsync")) {
					this.altsync = true;
				}
			}
		}
	}

	private boolean isAltSyncFeatureEnabled(String[] opts) {
		for (String str : opts) {
			if (str.equalsIgnoreCase("altsync") || str.equalsIgnoreCase("noaltsync"))
				return true;
		}
		return false;
	}

	/**
	 * Return a Perforce-standard representation of these options. This
	 * string is in the same format as used by the ClientOptions(String optionsString)
	 * constructor.
	 */
	public String toString() {
		if (this.altSyncFeatureEnabled) //means [no]altsync is present and server version >= 2023.1
			return (this.allWrite ? "allwrite" : "noallwrite") + (this.clobber ? " clobber" : " noclobber") + (this.compress ? " compress" : " nocompress") + (this.locked ? " locked" : " nolocked") + (this.modtime ? " modtime" : " nomodtime") + (this.rmdir ? " rmdir" : " normdir") + (this.altsync ? " altsync" : " noaltsync");
		else
			return (this.allWrite ? "allwrite" : "noallwrite") + (this.clobber ? " clobber" : " noclobber") + (this.compress ? " compress" : " nocompress") + (this.locked ? " locked" : " nolocked") + (this.modtime ? " modtime" : " nomodtime") + (this.rmdir ? " rmdir" : " normdir");
	}

	public boolean isAllWrite() {
		return allWrite;
	}

	public void setAllWrite(boolean allWrite) {
		this.allWrite = allWrite;
	}

	public boolean isClobber() {
		return clobber;
	}

	public void setClobber(boolean clobber) {
		this.clobber = clobber;
	}

	public boolean isCompress() {
		return compress;
	}

	public void setCompress(boolean compress) {
		this.compress = compress;
	}

	public boolean isLocked() {
		return locked;
	}

	public void setLocked(boolean locked) {
		this.locked = locked;
	}

	public boolean isModtime() {
		return modtime;
	}

	public void setModtime(boolean modtime) {
		this.modtime = modtime;
	}

	public boolean isRmdir() {
		return rmdir;
	}

	public void setRmdir(boolean rmdir) {
		this.rmdir = rmdir;
	}

	public boolean isaltSync() {
		return altsync;
	}

	public void setAltsync(boolean altsync) {
		this.altsync = altsync;
		this.altSyncFeatureEnabled = true;
	}
}
