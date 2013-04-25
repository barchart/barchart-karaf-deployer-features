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
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

import org.apache.felix.fileinstall.ArtifactUrlTransformer;
import org.apache.karaf.features.Feature;
import org.apache.karaf.features.FeaturesNamespaces;
import org.apache.karaf.features.FeaturesService;
import org.apache.karaf.features.FeaturesService.Option;
import org.apache.karaf.features.Repository;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * A deployment listener able to hot deploy a feature descriptor
 */
public class FeatureDeploymentListener2 implements ArtifactUrlTransformer,
		BundleListener {

	public static final String FEATURE_PATH = "org.apache.karaf.shell.features";

	private final Logger logger = LoggerFactory
			.getLogger(FeatureDeploymentListener2.class);

	private DocumentBuilderFactory dbf;
	private FeaturesService featuresService;
	private BundleContext bundleContext;

	public void setFeaturesService(FeaturesService featuresService) {
		this.featuresService = featuresService;
	}

	public FeaturesService getFeaturesService() {
		return featuresService;
	}

	public BundleContext getBundleContext() {
		return bundleContext;
	}

	public void setBundleContext(BundleContext bundleContext) {
		this.bundleContext = bundleContext;
	}

	/** component start */
	public void init() throws Exception {
		bundleContext.addBundleListener(this);
		// for (Bundle bundle : bundleContext.getBundles()) {
		// switch (bundle.getState()) {
		// case Bundle.RESOLVED:
		// case Bundle.STARTING:
		// case Bundle.ACTIVE:
		// bundleChanged(new BundleEvent(BundleEvent.RESOLVED, bundle));
		// }
		// }
	}

	/** component stop */
	public void destroy() throws Exception {
		bundleContext.removeBundleListener(this);
	}

	boolean isKnownFeaturesURI(String uri) {
		if (uri == null) {
			return true;
		}
		if (FeaturesNamespaces.URI_0_0_0.equalsIgnoreCase(uri)) {
			return true;
		}
		if (FeaturesNamespaces.URI_1_0_0.equalsIgnoreCase(uri)) {
			return true;
		}
		if (FeaturesNamespaces.URI_1_1_0.equalsIgnoreCase(uri)) {
			return true;
		}
		if (FeaturesNamespaces.URI_1_2_0.equalsIgnoreCase(uri)) {
			return true;
		}
		if (FeaturesNamespaces.URI_CURRENT.equalsIgnoreCase(uri)) {
			return true;
		}
		return false;
	}

	/** root xml tag */
	final static String TAG = "features";

	/** repo xml file extension */
	final static String EXT = "repo";

	/** repo install/uninstall state file */
	final static String CFG = "FeatureDeploymentListener.cfg";

	/** custom protocol */
	final static String PROTOCOL = "feature";

	public boolean canHandle(File artifact) {
		try {
			if (artifact.isFile() && artifact.getName().endsWith("." + EXT)) {
				Document doc = parse(artifact);
				String name = doc.getDocumentElement().getLocalName();
				String uri = doc.getDocumentElement().getNamespaceURI();
				if (TAG.equals(name)) {
					if (isKnownFeaturesURI(uri)) {
						return true;
					} else {
						logger.error("unknown features uri", new Exception(""
								+ uri));
					}
				}
			}
		} catch (Exception e) {
			logger.error(
					"Unable to parse deployed file "
							+ artifact.getAbsolutePath(), e);
		}
		return false;
	}

	/**
	 * We can't really install the feature right now and just return nothing. We
	 * would not be aware of the fact that the bundle has been uninstalled and
	 * therefore require the feature to be uninstalled. So instead, create a
	 * fake bundle with the file inside, which will be listened by this
	 * deployer: installation/uninstallation of the feature will be done while
	 * the bundle is installed/uninstalled.
	 */
	public URL transform(URL artifact) {
		try {
			return new URL(PROTOCOL, null, artifact.toString());
		} catch (Exception e) {
			logger.error("Unable to build feature bundle", e);
			return null;
		}
	}

	boolean isAutoInstall(Feature feature) {
		return feature.getInstall() != null
				&& feature.getInstall().equals(Feature.DEFAULT_INSTALL_MODE);
	}

	String propKeyPrefix(Bundle bundle) {
		return bundle.getSymbolicName() + "-" + bundle.getVersion();

	}

	String propCountKey(Bundle bundle) {
		return propKeyPrefix(bundle) + ".count";
	}

	int propCountValue(Properties props, Bundle bundle) throws Exception {
		String text = props.getProperty(propCountKey(bundle));
		if (text == null) {
			return 0;
		} else {
			return Integer.parseInt(text);
		}
	}

	String propUrlKey(Bundle bundle, int index) {
		return propKeyPrefix(bundle) + ".url." + index;
	}

	URL propUrlValue(Properties props, Bundle bundle, int index)
			throws Exception {
		String text = props.getProperty(propUrlKey(bundle, index));
		if (text == null) {
			return null;
		} else {
			return new URL(text);
		}
	}

	boolean featureInstall(Feature feature) {
		String name = feature.getName();
		String version = feature.getVersion();
		EnumSet<Option> options = EnumSet.of(Option.Verbose);
		try {
			featuresService.installFeature(name, version, options);
			return true;
		} catch (Exception e) {
			logger.error("Unable to uninstall feature: " + name, e);
			return false;
		}
	}

	boolean featureUninstall(Feature feature) {
		String name = feature.getName();
		String version = feature.getVersion();
		try {
			featuresService.uninstallFeature(name, version);
			return true;
		} catch (Exception e) {
			logger.error("Unable to uninstall feature: " + name, e);
			return false;
		}
	}

	boolean repoAdd(URL repoUrl) {
		try {
			featuresService.addRepository(repoUrl.toURI());
			return true;
		} catch (Exception e) {
			logger.error("Unable to add repository: " + repoUrl, e);
			return false;
		}
	}

	boolean repoRemove(URL repoUrl) {
		try {
			featuresService.removeRepository(repoUrl.toURI());
			return true;
		} catch (Exception e) {
			logger.error("Unable to remove repository: " + repoUrl, e);
			return false;
		}
	}

	void repoRemove(Properties props, Bundle bundle) throws Exception {
		for (int index = 0; index < propCountValue(props, bundle); index++) {
			URL repoUrl = propUrlValue(props, bundle, index);
			for (Repository repo : featuresService.listRepositories()) {
				if (repo.getURI().equals(repoUrl.toURI())) {
					for (Feature feature : repo.getFeatures()) {
						featureUninstall(feature);
					}
				}
			}
			repoRemove(repoUrl);
		}
	}

	File propFile() {
		return bundleContext.getDataFile(CFG);
	}

	Properties propLoad() throws Exception {
		File file = propFile();
		Properties props = new Properties();
		if (file.exists()) {
			InputStream input = new FileInputStream(file);
			try {
				props.load(input);
			} finally {
				input.close();
			}
		}
		return props;
	}

	void propSave(Properties props) throws Exception {
		OutputStream output = new FileOutputStream(propFile());
		try {
			props.store(output, null);
		} finally {
			output.close();
		}
	}

	void propRemove(Properties props, Bundle bundle) throws Exception {
		for (Iterator<Object> iterator = props.keySet().iterator(); iterator
				.hasNext();) {
			if (iterator.next().toString().startsWith(propKeyPrefix(bundle))) {
				iterator.remove();
			}
		}
	}

	static final String META_PATH = "/META-INF/" + FEATURE_PATH + "/";

	List<URL> repoUrlList(Bundle bundle) {

		List<URL> entryList = new ArrayList<URL>();

		Enumeration<URL> entryEnum = bundle.findEntries(META_PATH, "*." + EXT,
				false);

		if (entryEnum == null) {
			return entryList;
		}

		while (entryEnum.hasMoreElements()) {
			entryList.add(entryEnum.nextElement());
		}

		return entryList;

	}

	public void bundleChanged(BundleEvent bundleEvent) {

		Bundle bundle = bundleEvent.getBundle();

		List<URL> repoUrlList = repoUrlList(bundle);

		/** non repo bundle */
		if (repoUrlList.size() == 0) {
			return;
		}

		URL repoUrl = repoUrlList.get(0);

		/** add */
		if (bundleEvent.getType() == BundleEvent.RESOLVED) {
			synchronized (this) {
			}
		}

		/** remove*/
		if (bundleEvent.getType() == BundleEvent.UNINSTALLED) {
			synchronized (this) {
			}
		}

	}

	protected Document parse(File artifact) throws Exception {
		if (dbf == null) {
			dbf = DocumentBuilderFactory.newInstance();
			dbf.setNamespaceAware(true);
		}
		DocumentBuilder db = dbf.newDocumentBuilder();
		db.setErrorHandler(new ErrorHandler() {
			public void warning(SAXParseException exception)
					throws SAXException {
			}

			public void error(SAXParseException exception) throws SAXException {
			}

			public void fatalError(SAXParseException exception)
					throws SAXException {
				throw exception;
			}
		});
		return db.parse(artifact);
	}

}
