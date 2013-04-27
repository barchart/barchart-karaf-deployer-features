package org.apache.karaf.deployer.features;

import java.util.HashMap;
import java.util.Map;

import org.apache.karaf.features.Feature;

public class FeatureResult {

	public final Map<String, Feature> request = new HashMap<String, Feature>();

	public final Map<String, Feature> success = new HashMap<String, Feature>();

	public final Map<String, Feature> failure = new HashMap<String, Feature>();

	public final Map<String, Throwable> failureReason = new HashMap<String, Throwable>();

}
