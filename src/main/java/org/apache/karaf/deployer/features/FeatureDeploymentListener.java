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
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.felix.fileinstall.ArtifactUrlTransformer;
import org.apache.karaf.features.Feature;
import org.apache.karaf.features.FeaturesNamespaces;
import org.apache.karaf.features.FeaturesService;
import org.apache.karaf.features.FeaturesService.Option;
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
 */
public class FeatureDeploymentListener implements ArtifactUrlTransformer,
		SynchronousBundleListener {

	/** Repository feature.xml file extension managed by this component. */
	static final String EXTENSION = "repository";

	/** Features folder inside the wrapper bundle. */
	static final String FEATURE_PATH = "org.apache.karaf.shell.features";

	/** Features path inside the wrapper bundle jar. */
	static final String META_PATH = "/META-INF/" + FEATURE_PATH + "/";

	/** Deployer state properties. */
	static final String PROPERTIES = "deployer.properties";

	/** Feature deployer protocol, used by default feature deployer. */
	static final String PROTOCOL = "feature";

	/** Root node in feature.xml */
	static final String ROOT_NODE = "features";

	private volatile BundleContext bundleContext;

	private volatile DocumentBuilderFactory dbf;

	private volatile FeaturesService featuresService;

	private final Logger logger = LoggerFactory
			.getLogger(FeatureDeploymentListener.class);

	@Override
	public void bundleChanged(final BundleEvent event) {

		final Bundle bundle = event.getBundle();

		if (!hasRepoDescriptor(bundle)) {
			return;
		}

		final BundleEventType type = BundleEventType.from(event.getType());

		try {
			switch (type) {
			default:
				return;
			case STARTED:
				repoAdd(bundle);
				break;
			case STOPPED:
				repoRemove(bundle);
				break;
			}
			logger.info("Success: " + type + " / " + bundle);
		} catch (final Throwable e) {
			logger.error("Failure: " + type + " / " + bundle, e);
		}

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

	/**
	 * Component deactivate.
	 */
	public void destroy() throws Exception {
		bundleContext.removeBundleListener(this);
		logger.info("deactivate");
	}

	/**
	 * Activate auto-install features in a repository.
	 */
	void featureAdd(final Repository repo) throws Exception {
		final Feature[] featureArray = repo.getFeatures();
		for (final Feature feature : featureArray) {
			if (isAutoInstall(feature)) {
				featureAdd(repo, feature);
			}
		}
	}

	/**
	 * Install feature if missing, else count up.
	 */
	void featureAdd(final Repository repo, final Feature feature)
			throws Exception {

		final PropBean propBean = propBean();
		final boolean isMissing = isMissing(feature);

		if (propBean.checkIncrement(repo, feature)) {
			if (isMissing) {
				if (propBean.totalValue(feature) > 1) {
					logger.error(
							"Feature count error.",
							new IllegalStateException(
									"Feature is missing when should be installed."));
				}
				featureInstall(feature);
			}
		} else {
			logger.error("Feature count error.", new IllegalStateException(
					"Trying to install feature already added."));
		}
	}

	/**
	 * Install feature.
	 */
	void featureInstall(final Feature feature) throws Exception {
		final String name = feature.getName();
		final String version = feature.getVersion();
		featuresService.installFeature(name, version, options());
	}

	/**
	 * Deactivate auto-install features in a repository.
	 */
	void featureRemove(final Repository repo) throws Exception {
		final Feature[] featureArray = repo.getFeatures();
		for (final Feature feature : featureArray) {
			if (isAutoInstall(feature)) {
				featureRemove(repo, feature);
			}
		}
	}

	/**
	 * Count down, else uninstall feature if present.
	 */
	void featureRemove(final Repository repo, final Feature feature)
			throws Exception {

		final PropBean propBean = propBean();
		final boolean isPresent = isPresent(feature);

		if (propBean.checkDecrement(repo, feature)) {
			if (propBean.totalValue(feature) == 0) {
				if (isPresent) {
					featureUninstall(feature);
				} else {
					logger.error(
							"Feature count error.",
							new IllegalStateException(
									"Feature is missing when should be present."));
				}
			}
		} else {
			logger.error("Feature count error.", new IllegalStateException(
					"Trying to uninstall feature already removed."));
		}
	}

	/**
	 * Uinstall feature.
	 */
	void featureUninstall(final Feature feature) throws Exception {
		final String name = feature.getName();
		final String version = feature.getVersion();
		featuresService.uninstallFeature(name, version);
	}

	public BundleContext getBundleContext() {
		return bundleContext;
	}

	public FeaturesService getFeaturesService() {
		return featuresService;
	}

	/**
	 * Bundle contains stored feature.xml
	 */
	boolean hasRepoDescriptor(final Bundle bundle) {
		return repoUrl(bundle) != null;
	}

	/**
	 * Feature service contains named repository.
	 */
	boolean hasRepoRegistered(final String repoName) {
		return repo(repoName) != null;
	}

	/**
	 * Component activate.
	 */
	public void init() throws Exception {
		logger.info("activate");
		bundleContext.addBundleListener(this);
	}

	/**
	 * Feature auto-install mode.
	 */
	boolean isAutoInstall(final Feature feature) {
		return feature.getInstall() != null
				&& feature.getInstall().equals(Feature.DEFAULT_INSTALL_MODE);
	}

	/**
	 * Feature name space check.
	 */
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

	/**
	 * Feature not installed.
	 */
	boolean isMissing(final Feature feature) {
		return !isPresent(feature);
	}

	/**
	 * Feature is installed.
	 */
	boolean isPresent(final Feature feature) {
		return featuresService.isInstalled(feature);
	}

	/** Default feature install options. */
	EnumSet<Option> options() {
		return EnumSet.of(Option.Verbose, Option.PrintBundlesToRefresh);
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

	/**
	 * Properties bean.
	 */
	PropBean propBean() {
		return new PropBean(propFile());
	}

	/**
	 * Properties file.
	 */
	File propFile() {
		return bundleContext.getDataFile(PROPERTIES);
	}

	/**
	 * Find repository by name.
	 */
	Repository repo(final String repoName) {
		final Repository[] list = featuresService.listRepositories();
		for (final Repository repo : list) {
			if (repoName.equals(repo.getName())) {
				return repo;
			}
		}
		return null;
	}

	/**
	 * Add repository, process auto-install.
	 */
	synchronized void repoAdd(final Bundle bundle) throws Exception {

		final String repoName = repoName(bundle);
		final URL repoUrl = repoUrl(bundle);

		logger.info("Add: {} {}", repoName, repoUrl);

		if (hasRepoRegistered(repoName)) {
			throw new IllegalStateException("Repo is present: " + repoName);
		}

		/** Register repository w/o any feature install. */
		featuresService.addRepository(repoUrl.toURI(), false);

		final Repository repo = repo(repoName);

		featureAdd(repo);

	}

	/**
	 * Repository artifact id made from external feature.xml file name by url
	 * transformer.
	 */
	String repoName(final Bundle bundle) {
		return bundle.getSymbolicName();
	}

	/**
	 * Remove repository, process auto-install.
	 */
	synchronized void repoRemove(final Bundle bundle) throws Exception {

		final String repoName = repoName(bundle);
		final URL repoUrl = repoUrl(bundle);

		logger.info("Remove: {} {}", repoName, repoUrl);

		if (!hasRepoRegistered(repoName)) {
			throw new IllegalStateException("Repo is missing: " + repoName);
		}

		final Repository repo = repo(repoName);

		featureRemove(repo);

		/** Unregister repository w/o any feature uninstall. */
		featuresService.removeRepository(repoUrl.toURI(), false);

	}

	/**
	 * Repository feature.xml stored in the bundle.
	 */
	URL repoUrl(final Bundle bundle) {
		final List<URL> list = repoUrlList(bundle);
		switch (list.size()) {
		case 0:
			/** Non wrapper bundle. */
			return null;
		case 1:
			/** Wrapper bundle. */
			return list.get(0);
		default:
			logger.error("Repository bundle should have single url entry.",
					new IllegalStateException(bundle.toString()));
			return null;
		}
	}

	/**
	 * Repository feature.xml stored in the bundle.
	 */
	List<URL> repoUrlList(final Bundle bundle) {

		final Enumeration<URL> entryEnum = bundle.findEntries(META_PATH, "*."
				+ EXTENSION, false);

		if (entryEnum == null || !entryEnum.hasMoreElements()) {
			return Collections.emptyList();
		}

		final List<URL> repoUrlList = new ArrayList<URL>();

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
	 * Convert to feature wrapper URL.
	 */
	@Override
	public URL transform(final URL artifact) {
		try {
			return new URL(PROTOCOL, null, artifact.toString());
		} catch (final Exception e) {
			logger.error("Unable to build wrapper bundle", e);
			return null;
		}
	}

}
