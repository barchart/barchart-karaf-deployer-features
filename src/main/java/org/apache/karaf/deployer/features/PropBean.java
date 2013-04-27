/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.karaf.deployer.features;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

import org.apache.karaf.features.Feature;
import org.apache.karaf.features.Repository;

/**
 * Repository/feature install state persistence.
 */
public class PropBean {

	final File file;
	final Properties prop;

	PropBean(final File file) {
		this.file = file;
		this.prop = new Properties();
	}

	/**
	 * Decrement total/local counts if repository/feature is present.
	 */
	boolean checkDecrement(final Repository repo, final Feature feature)
			throws Exception {

		propLoad();
		final int total = totalValue(feature);
		final int local = localValue(repo, feature);

		if (local == 1) {
			totalValue(feature, total - 1);
			localValue(repo, feature, local - 1);
			propSave();
			return true;
		} else {
			return false;
		}

	}

	/**
	 * Increment total/local counts if repository/feature is missing.
	 */
	boolean checkIncrement(final Repository repo, final Feature feature)
			throws Exception {

		propLoad();
		final int total = totalValue(feature);
		final int local = localValue(repo, feature);

		if (local == 0) {
			totalValue(feature, total + 1);
			localValue(repo, feature, local + 1);
			propSave();
			return true;
		} else {
			return false;
		}

	}

	/**
	 * Load properties from file.
	 */
	void propLoad() throws Exception {
		if (!file.exists()) {
			return;
		}
		final InputStream input = new FileInputStream(file);
		try {
			prop.load(input);
		} finally {
			input.close();
		}
	}

	/**
	 * Save properties into file.
	 */
	void propSave() throws Exception {
		final OutputStream output = new FileOutputStream(file);
		try {
			prop.store(output, null);
		} finally {
			output.close();
		}
	}

	/**
	 * Repository/Feature count property name.
	 */
	String localKey(final Repository repo, final Feature feature) {
		return repo.getName() + "/" + feature.getId();
	}

	/**
	 * Load repository/feature count.
	 */
	int localValue(final Repository repo, final Feature feature)
			throws Exception {
		final String key = localKey(repo, feature);
		final String value = prop.getProperty(key, "0");
		return Integer.parseInt(value);
	}

	/**
	 * Save repository/feature count.
	 */
	int localValue(final Repository repo, final Feature feature, final int count)
			throws Exception {
		final String key = localKey(repo, feature);
		final String value = prop.getProperty(key, "0");
		prop.setProperty(key, Integer.toString(count));
		return Integer.parseInt(value);
	}

	/**
	 * Total feature count property name.
	 */
	String totalKey(final Feature feature) {
		return "total" + "/" + feature.getId();
	}

	/**
	 * Load total/feature count.
	 */
	int totalValue(final Feature feature) throws Exception {
		final String key = totalKey(feature);
		final String value = prop.getProperty(key, "0");
		return Integer.parseInt(value);
	}

	/**
	 * Save total/feature count.
	 */
	int totalValue(final Feature feature, final int count) throws Exception {
		final String key = totalKey(feature);
		final String value = prop.getProperty(key, "0");
		prop.setProperty(key, Integer.toString(count));
		return Integer.parseInt(value);
	}

}
