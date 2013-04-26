package org.apache.karaf.deployer.features;

import org.osgi.framework.BundleEvent;

public enum BundleEventType {

	INSTALLED(BundleEvent.INSTALLED), //
	RESOLVED(BundleEvent.RESOLVED), //
	LAZY_ACTIVATION(BundleEvent.LAZY_ACTIVATION), //
	STARTING(BundleEvent.STARTING), //
	STARTED(BundleEvent.STARTED), //
	STOPPING(BundleEvent.STOPPING), //
	STOPPED(BundleEvent.STOPPED), //
	UPDATED(BundleEvent.UPDATED), //
	UNRESOLVED(BundleEvent.UNRESOLVED), //
	UNINSTALLED(BundleEvent.UNINSTALLED), //

	UNKNOWN(0), //

	;

	public final int code;

	BundleEventType(final int code) {
		this.code = code;
	}

	public static BundleEventType from(final int code) {
		for (final BundleEventType known : BundleEventType.values()) {
			if (known.code == code) {
				return known;
			}
		}
		return UNKNOWN;
	}

}
