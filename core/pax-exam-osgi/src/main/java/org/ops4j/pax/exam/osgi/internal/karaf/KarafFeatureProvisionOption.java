/*
 * Copyright 2013 Christoph Läubrich
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ops4j.pax.exam.osgi.internal.karaf;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

import org.apache.karaf.xmlns.features.v1_0.Bundle;
import org.apache.karaf.xmlns.features.v1_0.Config;
import org.apache.karaf.xmlns.features.v1_0.ConfigFile;
import org.apache.karaf.xmlns.features.v1_0.Dependency;
import org.apache.karaf.xmlns.features.v1_0.Feature;
import org.apache.karaf.xmlns.features.v1_0.FeaturesRoot;
import org.ops4j.io.StreamUtils;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.TestContainerException;
import org.ops4j.pax.exam.options.UrlProvisionOption;
import org.ops4j.pax.exam.options.extra.WorkingDirectoryOption;
import org.ops4j.pax.exam.osgi.ConfigurationAdminOptions;
import org.ops4j.pax.exam.osgi.ConfigurationOption;
import org.ops4j.pax.exam.osgi.KarafFeatureOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation for {@link KarafFeatureOption}
 */
public class KarafFeatureProvisionOption implements KarafFeatureOption {

    private static final Logger LOG = LoggerFactory.getLogger(KarafFeatureProvisionOption.class);

    private static final ThreadLocal<Unmarshaller> UNMARSHALLER;

    static {
        try {
            final JAXBContext context = JAXBContext
                .newInstance(org.apache.karaf.xmlns.features.v1_0.ObjectFactory.class);
            UNMARSHALLER = new ThreadLocal<Unmarshaller>() {

                @Override
                protected Unmarshaller initialValue() {
                    try {
                        return context.createUnmarshaller();
                    }
                    catch (JAXBException e) {
                        throw new TestContainerException(
                            "can't create Unmarshaller for parsing XML", e);
                    }
                };
            };
        }
        catch (JAXBException e) {
            throw new TestContainerException("can't create JAXBContext for parsing XML", e);
        }
    }

    private final String repositoryUrl;
    private final Set<String> featuresSet;

    private int karafStartlevel = 60;

    private WorkingDirectoryOption directoryOption;

    /**
     * @param repositoryUrl
     *            the URL of the featurefile
     */
    public KarafFeatureProvisionOption(String repositoryUrl) {
        if (repositoryUrl == null) {
            throw new IllegalArgumentException("repositoryUrl can't be null");
        }
        this.repositoryUrl = repositoryUrl;
        featuresSet = new HashSet<String>();
    }

    @Override
    public KarafFeatureOption add(String... features) {
        featuresSet.addAll(Arrays.asList(features));
        return this;
    }

    @Override
    public KarafFeatureOption defaultStartLevel(int level) {
        karafStartlevel = level;
        return this;
    }

    @Override
    public KarafFeatureOption workingDir(WorkingDirectoryOption _directoryOption) {
        this.directoryOption = _directoryOption;
        return this;
    }

    @Override
    public Option toOption() {
        try {
            // create the URL
            URL url = new URL(repositoryUrl);
            List<Feature> features = new ArrayList<Feature>();
            FeaturesRoot featuresRoot = getFeaturesRoot(url);
            LOG.info("Provision feature repository with name {} from url {}",
                featuresRoot.getName(), repositoryUrl);
            addAllFeatures(features, featuresRoot, new HashSet<String>());
            List<Option> options = new ArrayList<Option>();
            for (Feature feature : features) {
                addFeatureOptions(feature, options);
            }
            return CoreOptions.composite(options.toArray(new Option[0]));
        }
        catch (MalformedURLException e) {
            throw new TestContainerException("can't parse URL", e);
        }
    }

    /**
     * @param features
     * @param featuresRoot
     */
    private static void addAllFeatures(List<Feature> features, FeaturesRoot featuresRoot,
        Set<String> scannedURIs) {
        List<Object> repositoryOrFeature = featuresRoot.getRepositoryOrFeature();
        for (Object object : repositoryOrFeature) {
            if (object instanceof Feature) {
                features.add((Feature) object);
            }
            else if (object instanceof String) {
                // This is a repository with additional dependencies
                String repro = (String) object;
                if (scannedURIs.contains(repro)) {
                    // Ignore and warn already read URIs
                    LOG.warn(
                        "It seems you have a cyclic dependency for repository URI {} the scanned features might not be complete!",
                        repro);
                }
                else {
                    scannedURIs.add(repro);
                    try {
                        URL url = new URL(repro);
                        FeaturesRoot repository = getFeaturesRoot(url);
                        addAllFeatures(features, repository, scannedURIs);
                    }
                    catch (MalformedURLException e) {
                        LOG.error(
                            "Can't parse repository URI {}, the scanned features might not be complete! ({})",
                            repro, e.toString());
                    }
                    // CHECKSTYLE:SKIP
                    catch (Exception e) {
                        LOG.error(
                            "Can't parse repository at URI {}, the scanned features might not be complete! ({})",
                            repro, e.toString());
                    }
                }
            }
        }

    }

    private static FeaturesRoot getFeaturesRoot(URL url) {
        try {
            Unmarshaller unmarshaller = UNMARSHALLER.get();
            Object object = unmarshaller.unmarshal(url);
            if (object instanceof JAXBElement<?>) {
                object = ((JAXBElement<?>) object).getValue();
            }
            if (object instanceof FeaturesRoot) {
                return (FeaturesRoot) object;
            }
            else {
                throw new TestContainerException("The parsed object is not of type FeaturesRoot");
            }
        }
        catch (JAXBException e) {
            throw new TestContainerException(
                "parsing the featurefile from url " + url + " failed!", e);
        }
    }

    /**
     * @param feature
     * @param options
     */
    private void addFeatureOptions(Feature feature, List<Option> options) {
        String name = feature.getName();
        if (featuresSet.contains(name)) {
            // The feature should be provisioned!
            String version = feature.getVersion();
            LOG.info("Provision feature {} with version {}", name, version);
            String resolver = feature.getResolver();
            if (resolver != null) {
                // TODO: We can (similar to the ConfigurationAdminOptions) create a tiny bundle here
                // that searches
                // for (&(objectClass=org.apache.karaf.features.Resolver)(name=resolver)) service
                // and try to resolve it at runtime then!
                // or we even can support some resolvers (e.g. OBR) natively...
                LOG.error(
                    "Using resolvers (specified in feature {}) is currently not supported (resolver specified: {}), the feature will be ignored!",
                    name, resolver);
                return;
            }
            List<Object> content = feature.getDetailsOrConfigOrConfigfile();
            for (Object object : content) {
                if (object instanceof Dependency) {
                    addDependency((Dependency) object, options);
                }
                else if (object instanceof Bundle) {
                    addBundle((Bundle) object, options);
                }
                else if (object instanceof Config) {
                    addConfig((Config) object, options);
                }
                else if (object instanceof ConfigFile) {
                    addConfigFile((ConfigFile) object, options);
                }
                else if (object instanceof String) {
                    // Long info displayed in features:info command result.
                    // We can ignore this here
                }
            }
        }

    }

    /**
     * Adds a {@link ConfigFile} to the options
     * 
     * @param configFile
     * @param options
     */
    private void addConfigFile(ConfigFile configFile, List<Option> options) {
        if (directoryOption != null) {
            try {
                URL url = new URL(configFile.getValue().trim());
                String finalname = configFile.getFinalname();
                String workingDirectory = directoryOption.getWorkingDirectory();
                File destinationFile = new File(workingDirectory, finalname);
                FileOutputStream outputStream = new FileOutputStream(destinationFile);
                try {
                    InputStream inputStream = url.openStream();
                    StreamUtils.copyStream(inputStream, outputStream, true);
                }
                finally {
                    outputStream.close();
                }
            }
            catch (IOException e) {
                LOG.error(
                    "The deployment of configFile {} failed and will not take place (final name = {})",
                    new Object[] { configFile.getValue(), configFile.getFinalname(), e });
            }
        }
        else {
            LOG.warn(
                "No working directory set, the deployment of configFile {} will not take place (final name = {})",
                configFile.getValue(), configFile.getFinalname());
        }

    }

    /**
     * Adds a {@link Config} to the options
     * 
     * @param config
     * @param options
     */
    private void addConfig(Config config, List<Option> options) {
        String name = config.getName();
        String value = config.getValue();
        int indexOf = name.indexOf('-');
        ConfigurationOption configuration;
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(value));
        }
        catch (IOException e) {
            LOG.error("Can't read the properties for configuration {}", name, e);
            return;
        }
        if (indexOf > -1) {
            // a factory configuration
            String fpid = name.substring(0, indexOf);
            configuration = ConfigurationAdminOptions.factoryConfiguration(fpid);
            LOG.info("Provision factory configuration for PID {} and values = {}", fpid, properties);
        }
        else {
            // a "normal" configuration
            configuration = ConfigurationAdminOptions.newConfiguration(name);
            LOG.info("Provision configuration for PID {} and values = {}", name, properties);
        }
        options.add(configuration.asOption());
    }

    /**
     * Adds a {@link Bundle} to the options
     * 
     * @param bundle
     * @param options
     */
    private void addBundle(Bundle bundle, List<Option> options) {
        Integer startLevel = bundle.getStartLevel();
        String uri = bundle.getValue();
        Boolean start = bundle.isStart();
        Boolean dependency = bundle.isDependency();
        UrlProvisionOption option = CoreOptions.bundle(uri);
        if (startLevel != null) {
            option.startLevel(startLevel);
        }
        else {
            option.startLevel(karafStartlevel);
        }
        if (start == null || start.booleanValue()) {
            // The default is to start the bundle...
            option.start(true);
        }
        else {
            option.start(false);
        }
        options.add(option);
        if (dependency != null && dependency.booleanValue()) {
            // TODO: support it...
            LOG.warn("The dependency option is currently not supported and will be ignored!");
        }

    }

    /**
     * "adds" a {@link Dependency} to the options
     * 
     * @param object
     * @param options
     */
    private void addDependency(Dependency object, List<Option> options) {
        if (!featuresSet.contains(object.getValue())) {
            LOG.info(
                "One of the features has a dependency to feature {} what is not part of this provision, make sure it will be provided by some other means",
                object.getValue());
        }
    }

}
