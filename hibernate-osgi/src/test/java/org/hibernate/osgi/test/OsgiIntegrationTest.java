/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2015, Red Hat Inc. or third-party contributors as
 * indicated by the @author tags or express copyright attribution
 * statements applied by the authors.  All third-party contributions are
 * distributed under license by Red Hat Inc.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.hibernate.osgi.test;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Calendar;
import java.util.Locale;
import java.util.Properties;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.spi.PersistenceProvider;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.selector.spi.StrategySelector;
import org.hibernate.cfg.Environment;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.internal.util.config.ConfigurationHelper;
import org.hibernate.osgi.test.client.DataPoint;
import org.hibernate.osgi.test.client.SomeService;
import org.hibernate.osgi.test.client.TestIntegrator;
import org.hibernate.osgi.test.client.TestStrategyRegistrationProvider;
import org.hibernate.osgi.test.client.TestTypeContributor;
import org.hibernate.type.BasicType;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;

import org.apache.karaf.features.BootFinished;
import org.apache.karaf.features.FeaturesService;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.ProbeBuilder;
import org.ops4j.pax.exam.TestProbeBuilder;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.karaf.options.LogLevelOption;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerClass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.CoreOptions.options;
import static org.ops4j.pax.exam.CoreOptions.repositories;
import static org.ops4j.pax.exam.CoreOptions.repository;
import static org.ops4j.pax.exam.CoreOptions.when;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.configureConsole;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.debugConfiguration;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.features;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.karafDistributionConfiguration;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.keepRuntimeFolder;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.logLevel;

/**
 * Tests for hibernate-osgi running within a Karaf container via PaxExam.
 *
 * @author Steve Ebersole
 */
@RunWith( PaxExam.class )
@ExamReactorStrategy( PerClass.class )
public class OsgiIntegrationTest {

	private static final boolean DEBUG = false;

	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Prepare the Karaf container

	@Configuration
	public Option[] config() throws Exception {
		final Properties paxExamEnvironment = loadPaxExamEnvironmentProperties();

		final boolean debug = ConfigurationHelper.getBoolean(
				"org.hibernate.testing.osgi.paxExam.debug",
				Environment.getProperties(),
				DEBUG
		);

		return options(
				when( debug ).useOptions( debugConfiguration( "5005", true ) ),
				karafDistributionConfiguration()
						.frameworkUrl( paxExamEnvironment.getProperty( "org.ops4j.pax.exam.container.karaf.distroUrl" ) )
						.karafVersion( paxExamEnvironment.getProperty( "org.ops4j.pax.exam.container.karaf.version" ) )
						.name( "Apache Karaf" )
						.unpackDirectory( new File( paxExamEnvironment.getProperty( "org.ops4j.pax.exam.container.karaf.unpackDir" ) ) )
						.useDeployFolder( false ),
				repositories(
						repository( "https://repository.jboss.org/nexus/content/groups/public-jboss/" )
								.id( "jboss-nexus" )
								.allowSnapshots()
				),
				configureConsole().ignoreLocalConsole().ignoreRemoteShell(),
				when( debug ).useOptions( keepRuntimeFolder() ),
				logLevel( LogLevelOption.LogLevel.INFO ),
				features( featureXmlUrl( paxExamEnvironment ), "hibernate-native", "hibernate-jpa" ),
				features( testingFeatureXmlUrl(), "hibernate-osgi-testing" )
		);
	}

	private static Properties loadPaxExamEnvironmentProperties() throws IOException {
		Properties props = new Properties();
		props.load( OsgiIntegrationTest.class.getResourceAsStream( "/pax-exam-environment.properties" ) );
		return props;
	}

	private static String featureXmlUrl(Properties paxExamEnvironment) throws MalformedURLException {
		return new File( paxExamEnvironment.getProperty( "org.hibernate.osgi.test.karafFeatureFile" ) ).toURI().toURL().toExternalForm();
	}

	private String testingFeatureXmlUrl() {
		return OsgiIntegrationTest.class.getClassLoader().getResource( "org/hibernate/osgi/test/testing-bundles.xml" )
				.toExternalForm();
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Prepare the PaxExam probe (the bundle to deploy)


	@ProbeBuilder
	public TestProbeBuilder probeConfiguration(TestProbeBuilder probe) {
		System.out.println( "Configuring probe..." );

		// Note : I found locally that this part is not needed.  But I am leaving this here as I might
		// 		someday have a need for tweaking the probe and I want to remember how it is done...

//		// attempt to override PaxExam's default of dynamically importing everything
//		probe.setHeader( Constants.DYNAMICIMPORT_PACKAGE, "" );
//		// and use defined imports instead
//		probe.setHeader(
//				Constants.IMPORT_PACKAGE,
//				"javassist.util.proxy"
//						+ ",javax.persistence"
//						+ ",javax.persistence.spi"
//						+ ",org.h2"
//						+ ",org.osgi.framework"
//						+ ",org.hibernate"
////						+ ",org.hibernate.boot.model"
////						+ ",org.hibernate.boot.registry.selector"
////						+ ",org.hibernate.boot.registry.selector.spi"
////						+ ",org.hibernate.cfg"
////						+ ",org.hibernate.engine.spi"
////						+ ",org.hibernate.integrator.spi"
////						+ ",org.hibernate.proxy"
////						+ ",org.hibernate.service"
////						+ ",org.hibernate.service.spi"
////						+ ",org.ops4j.pax.exam.options"
////						+ ",org.ops4j.pax.exam"
//		);
		probe.setHeader( Constants.BUNDLE_ACTIVATOR, "org.hibernate.osgi.test.client.OsgiTestActivator" );
		return probe;
	}

	@BeforeClass
	public static void setLocaleToEnglish() {
		Locale.setDefault( Locale.ENGLISH );
	}


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// The tests

	@Inject
	protected FeaturesService featuresService;

	@Inject
	BootFinished bootFinished;

	@Inject
	@SuppressWarnings("UnusedDeclaration")
	private BundleContext bundleContext;

	@Test
	public void testFeatureInstallation() throws Exception {
		assertTrue( featuresService.isInstalled( featuresService.getFeature( "hibernate-jpa" ) ) );
		assertTrue( featuresService.isInstalled( featuresService.getFeature( "hibernate-native" ) ) );
	}

	@Test
	public void testJpa() throws Exception {
		final ServiceReference serviceReference = bundleContext.getServiceReference( PersistenceProvider.class.getName() );
		final PersistenceProvider persistenceProvider = (PersistenceProvider) bundleContext.getService( serviceReference );
		final EntityManagerFactory emf = persistenceProvider.createEntityManagerFactory( "hibernate-osgi-test", null );

		EntityManager em = emf.createEntityManager();
		em.getTransaction().begin();
		em.persist( new DataPoint( "Brett" ) );
		em.getTransaction().commit();
		em.close();

		em = emf.createEntityManager();
		em.getTransaction().begin();
		DataPoint dp = em.find( DataPoint.class, 1 );
		assertNotNull( dp );
		assertEquals( "Brett", dp.getName() );
		em.getTransaction().commit();
		em.close();

		em = emf.createEntityManager();
		em.getTransaction().begin();
		dp = em.find( DataPoint.class, 1 );
		dp.setName( "Brett2" );
		em.getTransaction().commit();
		em.close();

		em = emf.createEntityManager();
		em.getTransaction().begin();
		em.createQuery( "delete from DataPoint" ).executeUpdate();
		em.getTransaction().commit();
		em.close();

		em = emf.createEntityManager();
		em.getTransaction().begin();
		dp = em.find( DataPoint.class, 1 );
		assertNull( dp );
		em.getTransaction().commit();
		em.close();
	}

	@Test
	public void testNative() throws Exception {
		final ServiceReference sr = bundleContext.getServiceReference( SessionFactory.class.getName() );
		final SessionFactory sf = (SessionFactory) bundleContext.getService( sr );

		Session s = sf.openSession();
		s.getTransaction().begin();
		s.persist( new DataPoint( "Brett" ) );
		s.getTransaction().commit();
		s.close();

		s = sf.openSession();
		s.getTransaction().begin();
		DataPoint dp = (DataPoint) s.get( DataPoint.class, 1 );
		assertNotNull( dp );
		assertEquals( "Brett", dp.getName() );
		s.getTransaction().commit();
		s.close();

		dp.setName( "Brett2" );

		s = sf.openSession();
		s.getTransaction().begin();
		s.update( dp );
		s.getTransaction().commit();
		s.close();

		s = sf.openSession();
		s.getTransaction().begin();
		dp = (DataPoint) s.get( DataPoint.class, 1 );
		assertNotNull( dp );
		assertEquals( "Brett2", dp.getName() );
		s.getTransaction().commit();
		s.close();

		s = sf.openSession();
		s.getTransaction().begin();
		s.createQuery( "delete from DataPoint" ).executeUpdate();
		s.getTransaction().commit();
		s.close();

		s = sf.openSession();
		s.getTransaction().begin();
		dp = (DataPoint) s.get( DataPoint.class, 1 );
		assertNull( dp );
		s.getTransaction().commit();
		s.close();
	}

	@Test
	public void testExtensionPoints() throws Exception {
		final ServiceReference sr = bundleContext.getServiceReference( SessionFactory.class.getName() );
		final SessionFactoryImplementor sfi = (SessionFactoryImplementor) bundleContext.getService( sr );

		assertTrue( TestIntegrator.passed() );

		Class impl = sfi.getServiceRegistry().getService( StrategySelector.class ).selectStrategyImplementor( Calendar.class, TestStrategyRegistrationProvider.GREGORIAN );
		assertNotNull( impl );

		BasicType basicType = sfi.getTypeResolver().basic( TestTypeContributor.NAME );
		assertNotNull( basicType );
	}

	@Test
	public void testServiceContributorDiscovery() throws Exception {
		final ServiceReference sr = bundleContext.getServiceReference( SessionFactory.class.getName() );
		final SessionFactoryImplementor sfi = (SessionFactoryImplementor) bundleContext.getService( sr );

		assertNotNull( sfi.getServiceRegistry().getService( SomeService.class ) );
	}
}
