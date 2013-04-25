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
 * A deployment listener able to hot deploy (install/uninstall) a repository
 * descriptor.
 * <p>
 * Assumptions:
 * <p>
 * feature.xml file must have external file name based on artifact id.
 * <p>
 * feature.xml file must have internal root name based on artifact id.
 * <p>
 * feature.xml file must have file extension managed by this component.
 * <p>
 * all features inside feature.xml file are managed as single logical unit.
 */
public class FeatureDeploymentListener implements ArtifactUrlTransformer,
		BundleListener {

	/** repository feature.xml file extension */
	static final String EXTENSION = "repository";

	/** features folder inside the bundle */
	static final String FEATURE_PATH = "org.apache.karaf.shell.features";

	/** features path inside the bundle jar */
	static final String META_PATH = "/META-INF/" + FEATURE_PATH + "/";

	/** feature deployer protocol, used by default feature deployer */
	static final String PROTOCOL = "feature";

	/** root tag in feature.xml */
	static final String ROOT_NODE = "features";

	private BundleContext bundleContext;

	private DocumentBuilderFactory dbf;

	private FeaturesService featuresService;

	private final Logger logger = LoggerFactory
			.getLogger(FeatureDeploymentListener.class);

	public void bundleChanged(final BundleEvent event) {

		final Bundle bundle = event.getBundle();

		final List<URL> repoUrlList = repoUrlList(bundle);

		switch (repoUrlList.size()) {
		case 0:
			/** non repo bundle */
			return;
		case 1:
			/** repo bundle */
			break;
		default:
			logger.error("Repo bundle should have single entry.",
					new IllegalStateException());
			return;
		}

		/** artifact id made from feature.xml file name by url transformer */
		final String repoName = bundle.getSymbolicName().intern();

		final URL repoUrl = repoUrlList.get(0);

		/** add */
		if (event.getType() == BundleEvent.RESOLVED) {
			new Thread("# repo add " + repoName) {
				public void run() {
					while (true) {
						synchronized (repoName) {
							if (!hasRepo(repoName)) {
								repoAdd(repoUrl);
								return;
							}
						}
						try {
							logger.info("Waiting for repo remove: " + repoName);
							Thread.sleep(1000);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
				}
			}.start();
		}

		/** remove */
		if (event.getType() == BundleEvent.UNINSTALLED) {
			new Thread("# repo remove " + repoName) {
				public void run() {
					synchronized (repoName) {
						if (hasRepo(repoName)) {
							repoRemove(repoUrl);
						}
					}
				}
			}.start();
		}

	}

	public boolean canHandle(File artifact) {
		try {
			if (artifact.isFile()
					&& artifact.getName().endsWith("." + EXTENSION)) {
				Document doc = parse(artifact);
				String name = doc.getDocumentElement().getLocalName();
				String uri = doc.getDocumentElement().getNamespaceURI();
				if (ROOT_NODE.equals(name)) {
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

	/** component stop */
	public void destroy() throws Exception {
		bundleContext.removeBundleListener(this);
	}

	public BundleContext getBundleContext() {
		return bundleContext;
	}

	public FeaturesService getFeaturesService() {
		return featuresService;
	}

	boolean hasRepo(String repoName) {
		Repository[] list = featuresService.listRepositories();
		for (Repository repo : list) {
			if (repoName.equals(repo.getName())) {
				return true;
			}
		}
		return false;
	}

	/** component start */
	public void init() throws Exception {
		bundleContext.addBundleListener(this);
	}

	boolean isAutoInstall(Feature feature) {
		return feature.getInstall() != null
				&& feature.getInstall().equals(Feature.DEFAULT_INSTALL_MODE);
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

	protected Document parse(File artifact) throws Exception {
		if (dbf == null) {
			dbf = DocumentBuilderFactory.newInstance();
			dbf.setNamespaceAware(true);
		}
		DocumentBuilder db = dbf.newDocumentBuilder();
		db.setErrorHandler(new ErrorHandler() {
			public void error(SAXParseException exception) throws SAXException {
			}

			public void fatalError(SAXParseException exception)
					throws SAXException {
				throw exception;
			}

			public void warning(SAXParseException exception)
					throws SAXException {
			}
		});
		return db.parse(artifact);
	}

	boolean repoAdd(URL repoUrl) {
		try {
			featuresService.addRepository(repoUrl.toURI(), true);
			return true;
		} catch (Exception e) {
			logger.error("Failed to add repository: " + repoUrl, e);
			return false;
		}
	}

	boolean repoRemove(URL repoUrl) {
		try {
			featuresService.removeRepository(repoUrl.toURI(), true);
			return true;
		} catch (Exception e) {
			logger.error("Failed to remove repository: " + repoUrl, e);
			return false;
		}
	}

	/** url of repository file baked into the bundle */
	List<URL> repoUrlList(Bundle bundle) {

		List<URL> repoUrlList = new ArrayList<URL>();

		Enumeration<URL> entryEnum = bundle.findEntries(META_PATH, "*."
				+ EXTENSION, false);

		if (entryEnum == null) {
			return repoUrlList;
		}

		while (entryEnum.hasMoreElements()) {
			repoUrlList.add(entryEnum.nextElement());
		}

		return repoUrlList;

	}

	public void setBundleContext(BundleContext bundleContext) {
		this.bundleContext = bundleContext;
	}

	public void setFeaturesService(FeaturesService featuresService) {
		this.featuresService = featuresService;
	}

	/**
	 * 
	 */
	public URL transform(URL artifact) {
		try {
			return new URL(PROTOCOL, null, artifact.toString());
		} catch (Exception e) {
			logger.error("Unable to build feature bundle", e);
			return null;
		}
	}

}
