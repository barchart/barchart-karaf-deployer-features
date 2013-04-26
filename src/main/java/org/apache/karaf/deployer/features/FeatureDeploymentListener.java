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
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.felix.fileinstall.ArtifactUrlTransformer;
import org.apache.karaf.features.Feature;
import org.apache.karaf.features.FeaturesNamespaces;
import org.apache.karaf.features.FeaturesService;
import org.apache.karaf.features.Repository;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.SynchronousBundleListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
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
		SynchronousBundleListener {

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

	private volatile BundleContext bundleContext;

	private volatile DocumentBuilderFactory dbf;

	private volatile FeaturesService featuresService;

	private final Logger logger = LoggerFactory
			.getLogger(FeatureDeploymentListener.class);

	@Override
	public void bundleChanged(final BundleEvent event) {

		final Bundle bundle = event.getBundle();
		final BundleEventType type = BundleEventType.from(event.getType());
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
		final String repoName = bundle.getSymbolicName();

		final URL repoUrl = repoUrlList.get(0);

		logger.info("bundle : " + bundle);
		logger.info("type   : " + type);
		logger.info("repoUrl : " + repoUrl);

		switch (type) {

		case STARTED:
			logger.info("# STARTED");
			if (hasRepo(repoName)) {
				logger.error("Repo name is present.",
						new IllegalStateException(repoName));
			} else {
				repoAdd(repoUrl);
			}
			break;

		case STOPPED:
			logger.info("# STOPPED");
			if (hasRepo(repoName)) {
				repoRemove(repoUrl);
			} else {
				logger.error("Repo name is missing.",
						new IllegalStateException(repoName));
			}
			break;

		}

		logger.info("@@@");

	}

	@Override
	public boolean canHandle(final File artifact) {
		try {
			if (artifact.isFile()
					&& artifact.getName().endsWith("." + EXTENSION)) {
				final Document doc = parse(artifact);
				final String name = doc.getDocumentElement().getLocalName();
				final String uri = doc.getDocumentElement().getNamespaceURI();
				if (ROOT_NODE.equals(name)) {
					if (isKnownFeaturesURI(uri)) {
						return true;
					} else {
						logger.error("unknown features uri", new Exception(""
								+ uri));
					}
				}
			}
		} catch (final Exception e) {
			logger.error(
					"Unable to parse deployed file "
							+ artifact.getAbsolutePath(), e);
		}
		return false;
	}

	/** component stop */
	public void destroy() throws Exception {
		bundleContext.removeBundleListener(this);
		logger.info("deactivate");
	}

	public BundleContext getBundleContext() {
		return bundleContext;
	}

	public FeaturesService getFeaturesService() {
		return featuresService;
	}

	boolean hasRepo(final String repoName) {
		final Repository[] list = featuresService.listRepositories();
		if (list == null) {
			return false;
		}
		for (final Repository repo : list) {
			if (repoName.equals(repo.getName())) {
				return true;
			}
		}
		return false;
	}

	/** component start */
	public void init() throws Exception {
		logger.info("activate");
		bundleContext.addBundleListener(this);
	}

	boolean isAutoInstall(final Feature feature) {
		return feature.getInstall() != null
				&& feature.getInstall().equals(Feature.DEFAULT_INSTALL_MODE);
	}

	boolean isKnownFeaturesURI(final String uri) {
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

	protected Document parse(final File artifact) throws Exception {
		if (dbf == null) {
			dbf = DocumentBuilderFactory.newInstance();
			dbf.setNamespaceAware(true);
		}
		final DocumentBuilder db = dbf.newDocumentBuilder();
		db.setErrorHandler(new ErrorHandler() {
			@Override
			public void error(final SAXParseException exception)
					throws SAXException {
			}

			@Override
			public void fatalError(final SAXParseException exception)
					throws SAXException {
				throw exception;
			}

			@Override
			public void warning(final SAXParseException exception)
					throws SAXException {
			}
		});
		return db.parse(artifact);
	}

	boolean repoAdd(final URL repoUrl) {
		logger.info("### ADD");
		try {
			featuresService.addRepository(repoUrl.toURI(), true);
			logger.info("OK: add repository: " + repoUrl);
			return true;
		} catch (final Throwable e) {
			logger.error("Failed to add repository: " + repoUrl, e);
			return false;
		}
	}

	boolean repoRemove(final URL repoUrl) {
		logger.info("### REMOVE");
		try {
			featuresService.removeRepository(repoUrl.toURI(), true);
			logger.info("OK: remove repository: " + repoUrl);
			return true;
		} catch (final Throwable e) {
			logger.error("Failed to remove repository: " + repoUrl, e);
			return false;
		}
	}

	/** url of repository file baked into the bundle */
	List<URL> repoUrlList(final Bundle bundle) {

		final List<URL> repoUrlList = new ArrayList<URL>();

		final Enumeration<URL> entryEnum = bundle.findEntries(META_PATH, "*."
				+ EXTENSION, false);

		if (entryEnum == null) {
			return repoUrlList;
		}

		while (entryEnum.hasMoreElements()) {
			repoUrlList.add(entryEnum.nextElement());
		}

		return repoUrlList;

	}

	public void setBundleContext(final BundleContext bundleContext) {
		this.bundleContext = bundleContext;
	}

	public void setFeaturesService(final FeaturesService featuresService) {
		this.featuresService = featuresService;
	}

	/**
	 * 
	 */
	@Override
	public URL transform(final URL artifact) {
		try {
			return new URL(PROTOCOL, null, artifact.toString());
		} catch (final Exception e) {
			logger.error("Unable to build feature bundle", e);
			return null;
		}
	}

}
