/*
 * %W% %E%
 * 
 * Copyright (c) 2006, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

package com.sun.jmx.interceptor;

// java import
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.AccessControlContext;
import java.security.Permission;
import java.security.ProtectionDomain;
import java.security.AccessController;
import java.security.PrivilegedAction;

// JMX import
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.InvalidAttributeValueException;
import javax.management.JMException;
import javax.management.JMRuntimeException;
import javax.management.ListenerNotFoundException;
import javax.management.MalformedObjectNameException;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanPermission;
import javax.management.MBeanRegistration;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MBeanServerDelegate;
import javax.management.MBeanServerNotification;
import javax.management.MBeanTrustPermission;
import javax.management.NotCompliantMBeanException;
import javax.management.Notification;
import javax.management.NotificationBroadcaster;
import javax.management.NotificationEmitter;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.QueryEval;
import javax.management.QueryExp;
import javax.management.ReflectionException;
import javax.management.RuntimeErrorException;
import javax.management.RuntimeMBeanException;
import javax.management.RuntimeOperationsException;

// JMX RI
import com.sun.jmx.mbeanserver.DynamicMBean2;
import com.sun.jmx.mbeanserver.ModifiableClassLoaderRepository;
import com.sun.jmx.mbeanserver.MBeanInstantiator;
import com.sun.jmx.mbeanserver.MXBeanSupport;
import com.sun.jmx.mbeanserver.Repository;
import com.sun.jmx.mbeanserver.NamedObject;
import com.sun.jmx.defaults.ServiceName;
import com.sun.jmx.mbeanserver.Introspector;
import com.sun.jmx.remote.util.EnvHelp;
import com.sun.jmx.trace.Trace;

/**
 * This is the default class for MBean manipulation on the agent side. It
 * contains the methods necessary for the creation, registration, and
 * deletion of MBeans as well as the access methods for registered MBeans.
 * This is the core component of the JMX infrastructure.
 * <P>
 * Every MBean which is added to the MBean server becomes manageable: its attributes and operations
 * become remotely accessible through the connectors/adaptors connected to that MBean server.
 * A Java object cannot be registered in the MBean server unless it is a JMX compliant MBean.
 * <P>
 * When an MBean is registered or unregistered in the MBean server an
 * {@link javax.management.MBeanServerNotification MBeanServerNotification}
 * Notification is emitted. To register an object as listener to MBeanServerNotifications
 * you should call the MBean server method {@link #addNotificationListener addNotificationListener} with <CODE>ObjectName</CODE>
 * the <CODE>ObjectName</CODE> of the {@link javax.management.MBeanServerDelegate MBeanServerDelegate}.
 * This <CODE>ObjectName</CODE> is:
 * <BR>
 * <CODE>JMImplementation:type=MBeanServerDelegate</CODE>.
 *
 * @since 1.5
 * @since.unbundled JMX RI 1.2
 */
public class DefaultMBeanServerInterceptor implements MBeanServerInterceptor {

    /** The MBeanInstantiator object used by the 
     *  DefaultMBeanServerInterceptor */
    private final transient MBeanInstantiator instantiator;

    /** The MBean server object that is associated to the 
     *  DefaultMBeanServerInterceptor */
    private transient MBeanServer server = null;

    /** The MBean server object taht associated to the 
     *  DefaultMBeanServerInterceptor */
    private final transient MBeanServerDelegate delegate;

    /** The Repository object used by the DefaultMBeanServerInterceptor */
    private final transient Repository repository;

    /** Wrappers for client listeners.  */
    /* See the comment before addNotificationListener below.  */
    private final transient
	WeakHashMap<ListenerWrapper, WeakReference<ListenerWrapper>>
	    listenerWrappers =
		new WeakHashMap<ListenerWrapper,
				WeakReference<ListenerWrapper>>();

    /** The default domain of the object names */
    private final String domain;

    /** True if the repository perform queries, false otherwise */
    private boolean queryByRepo;

    /** The sequence number identifyng the notifications sent */
    // Now sequence number is handled by MBeanServerDelegate.
    // private int sequenceNumber=0;

    /** The name of this class to be used for tracing */
    private final static String dbgTag = "DefaultMBeanServerInterceptor";

    /**
     * Creates a DefaultMBeanServerInterceptor with the specified 
     * repository instance.
     * <p>Do not forget to call <code>initialize(outer,delegate)</code>
     * before using this object.
     * @param outer A pointer to the MBeanServer object that must be
     *        passed to the MBeans when invoking their
     *        {@link javax.management.MBeanRegistration} interface.
     * @param delegate A pointer to the MBeanServerDelegate associated
     *        with the new MBeanServer. The new MBeanServer must register
     *        this MBean in its MBean repository.
     * @param instantiator The MBeanInstantiator that will be used to
     *        instantiate MBeans and take care of class loading issues.
     * @param metadata The MetaData object that will be used by the 
     *        MBean server in order to invoke the MBean interface of
     *        the registered MBeans.
     * @param repository The repository to use for this MBeanServer
     */
    public DefaultMBeanServerInterceptor(MBeanServer         outer, 
					 MBeanServerDelegate delegate,
					 MBeanInstantiator   instantiator, 
					 Repository          repository)  {
	if (outer == null) throw new 
	    IllegalArgumentException("outer MBeanServer cannot be null");
	if (delegate == null) throw new 
	    IllegalArgumentException("MBeanServerDelegate cannot be null");
	if (instantiator == null) throw new 
	    IllegalArgumentException("MBeanInstantiator cannot be null");
	if (repository == null) throw new
	    IllegalArgumentException("Repository cannot be null");

	this.server   = outer;
	this.delegate = delegate; 
	this.instantiator = instantiator;
	this.repository   = repository;
	this.domain       = repository.getDefaultDomain();
    }

    public ObjectInstance createMBean(String className, ObjectName name)
        throws ReflectionException, InstanceAlreadyExistsException,
               MBeanRegistrationException, MBeanException,
               NotCompliantMBeanException {

	return createMBean(className, name, (Object[]) null, (String[]) null);

    }

    public ObjectInstance createMBean(String className, ObjectName name,
                                      ObjectName loaderName)
        throws ReflectionException, InstanceAlreadyExistsException,
               MBeanRegistrationException, MBeanException,
               NotCompliantMBeanException, InstanceNotFoundException {

	return createMBean(className, name, loaderName, (Object[]) null,
			   (String[]) null);
    }

    public ObjectInstance createMBean(String className, ObjectName name,
				      Object[] params, String[] signature)
        throws ReflectionException, InstanceAlreadyExistsException,
	       MBeanRegistrationException, MBeanException,
               NotCompliantMBeanException  {

	try {
	    return createMBean(className, name, null, true,
			       params, signature);
	} catch (InstanceNotFoundException e) {
	    /* Can only happen if loaderName doesn't exist, but we just
	       passed null, so we shouldn't get this exception.  */
	    throw EnvHelp.initCause(
	        new IllegalArgumentException("Unexpected exception: " + e), e);
	}
    }

    public ObjectInstance createMBean(String className, ObjectName name,
				      ObjectName loaderName,
				      Object[] params, String[] signature)
        throws ReflectionException, InstanceAlreadyExistsException,
	       MBeanRegistrationException, MBeanException,
               NotCompliantMBeanException, InstanceNotFoundException  {

	return createMBean(className, name, loaderName, false,
			   params, signature);
    }

    private ObjectInstance createMBean(String className, ObjectName name,
				       ObjectName loaderName,
				       boolean withDefaultLoaderRepository,
				       Object[] params, String[] signature)
        throws ReflectionException, InstanceAlreadyExistsException,
	       MBeanRegistrationException, MBeanException,
               NotCompliantMBeanException, InstanceNotFoundException {

        ObjectName logicalName = name;
        Class theClass;

	if (className == null) {
	    final RuntimeException wrapped =
		new IllegalArgumentException("The class name cannot be null");
	    throw new RuntimeOperationsException(wrapped,
                      "Exception occurred during MBean creation");
	}

	if (name != null) {
	    if (name.isPattern()) {
		final RuntimeException wrapped =
		    new IllegalArgumentException("Invalid name->" +
						 name.toString());
		final String msg = "Exception occurred during MBean creation";
		throw new RuntimeOperationsException(wrapped, msg);
	    }

	    name = nonDefaultDomain(name);
	}

	checkMBeanPermission(className, null, null, "instantiate");
	checkMBeanPermission(className, null, name, "registerMBean");

	/* Load the appropriate class. */
	if (withDefaultLoaderRepository) {
	    if (isTraceOn()) {
		trace(dbgTag, "createMBean", "ClassName = " + className +
		      ",ObjectName = " + name);
	    }
	    theClass =
		instantiator.findClassWithDefaultLoaderRepository(className);
	} else if (loaderName == null) {
	    if (isTraceOn()) {
		trace(dbgTag, "createMBean", "ClassName = " + className +
		      ",ObjectName = " + name + " Loader name = null");
	    }

	    theClass = instantiator.findClass(className,
				  server.getClass().getClassLoader());
	} else {
	    loaderName = nonDefaultDomain(loaderName);

	    if (isTraceOn()) {
                trace(dbgTag, "createMBean", "ClassName = " + className +
		      ",ObjectName = " + name + ",Loader name = "+
		      loaderName.toString());
            }

	    theClass = instantiator.findClass(className, loaderName);
	}

	checkMBeanTrustPermission(theClass);

	// Check that the MBean can be instantiated by the MBeanServer.
	Introspector.testCreation(theClass);

        // Check the JMX MBean compliance of the class
        Introspector.checkCompliance(theClass);

	Object moi= instantiator.instantiate(theClass, params,  signature,
					     server.getClass().getClassLoader());

        final String infoClassName = getNewMBeanClassName(moi);

	return registerObject(infoClassName, moi, name);
    }

    public ObjectInstance registerMBean(Object object, ObjectName name)
	throws InstanceAlreadyExistsException, MBeanRegistrationException,
	NotCompliantMBeanException  {

	// ------------------------------
	// ------------------------------
        Class theClass = object.getClass();

        Introspector.checkCompliance(theClass);

	final String infoClassName = getNewMBeanClassName(object);

	checkMBeanPermission(infoClassName, null, name, "registerMBean");
	checkMBeanTrustPermission(theClass);

	return registerObject(infoClassName, object, name);
    }

    private static String getNewMBeanClassName(Object mbeanToRegister)
            throws NotCompliantMBeanException {
        if (mbeanToRegister instanceof DynamicMBean) {
            DynamicMBean mbean = (DynamicMBean) mbeanToRegister;
            final String name;
            try {
                name = mbean.getMBeanInfo().getClassName();
            } catch (Exception e) {
                // Includes case where getMBeanInfo() returns null
                NotCompliantMBeanException ncmbe =
                    new NotCompliantMBeanException("Bad getMBeanInfo()");
                ncmbe.initCause(e);
                throw ncmbe;
            }
            if (name == null) {
                final String msg = "MBeanInfo has null class name";
                throw new NotCompliantMBeanException(msg);
            }
            return name;
        } else
            return mbeanToRegister.getClass().getName();
    }

    private final Set<ObjectName> beingUnregistered =
	new HashSet<ObjectName>();

    public void unregisterMBean(ObjectName name)
	    throws InstanceNotFoundException, MBeanRegistrationException  {

        if (name == null) {
	    final RuntimeException wrapped =
		new IllegalArgumentException("Object name cannot be null");
            throw new RuntimeOperationsException(wrapped,
                      "Exception occurred trying to unregister the MBean");
        }

	name = nonDefaultDomain(name);

	/* The semantics of preDeregister are tricky.  If it throws an
	   exception, then the unregisterMBean fails.  This allows an
	   MBean to refuse to be unregistered.  If it returns
	   successfully, then the unregisterMBean can proceed.  In
	   this case the preDeregister may have cleaned up some state,
	   and will not expect to be called a second time.  So if two
	   threads try to unregister the same MBean at the same time
	   then one of them must wait for the other one to either (a)
	   call preDeregister and get an exception or (b) call
	   preDeregister successfully and unregister the MBean.
	   Suppose thread T1 is unregistering an MBean and thread T2
	   is trying to unregister the same MBean, so waiting for T1.
	   Then a deadlock is possible if the preDeregister for T1
	   ends up needing a lock held by T2.  Given the semantics
	   just described, there does not seem to be any way to avoid
	   this.  This will not happen to code where it is clear for
	   any given MBean what thread may unregister that MBean.

	   On the other hand we clearly do not want a thread that is
	   unregistering MBean A to have to wait for another thread
	   that is unregistering another MBean B (see bug 6318664).  A
	   deadlock in this situation could reasonably be considered
	   gratuitous.  So holding a global lock across the
	   preDeregister call would be bad.

	   So we have a set of ObjectNames that some thread is
	   currently unregistering.  When a thread wants to unregister
	   a name, it must first check if the name is in the set, and
	   if so it must wait.  When a thread successfully unregisters
	   a name it removes the name from the set and notifies any
	   waiting threads that the set has changed.
	
	   This implies that we must be very careful to ensure that
	   the name is removed from the set and waiters notified, no
	   matter what code path is taken.  */

        synchronized (beingUnregistered) {
            while (beingUnregistered.contains(name)) {
                try {
                    beingUnregistered.wait();
                } catch (InterruptedException e) {
                    throw new MBeanRegistrationException(e, e.toString());
                    // pretend the exception came from preDeregister;
                    // in another execution sequence it could have
                }
            }
            beingUnregistered.add(name);
        }

        try {
            exclusiveUnregisterMBean(name);
        } finally {
            synchronized (beingUnregistered) {
                beingUnregistered.remove(name);
                beingUnregistered.notifyAll();
            }
        }
    }
    
    private void exclusiveUnregisterMBean(ObjectName name)
            throws InstanceNotFoundException, MBeanRegistrationException {

        DynamicMBean instance = getMBean(name);
	// may throw InstanceNotFoundException

	checkMBeanPermission(instance, null, name, "unregisterMBean");

	if (instance instanceof MBeanRegistration)
            preDeregisterInvoke((MBeanRegistration) instance);

        synchronized (this) {
            repository.remove(name);
            // may throw InstanceNotFoundException
        }

        /**
         * Checks if the unregistered MBean is a ClassLoader
         * If so, it removes the  MBean from the default loader repository.
         */

        Object resource = getResource(instance);
        if (resource instanceof ClassLoader
            && resource != server.getClass().getClassLoader()) {
            final ModifiableClassLoaderRepository clr =
                instantiator.getClassLoaderRepository();
            if (clr != null) clr.removeClassLoader(name);
        }

	// ---------------------
	// Send deletion event
	// ---------------------
	if (isTraceOn()) {
	    trace("unregisterMBean", "Send delete notification of object "
		  + name.getCanonicalName());
	}
	sendNotification(MBeanServerNotification.UNREGISTRATION_NOTIFICATION,
			 name);

	if (instance instanceof MBeanRegistration)
	    postDeregisterInvoke((MBeanRegistration) instance);
    }

    public ObjectInstance getObjectInstance(ObjectName name)
	    throws InstanceNotFoundException {

	name = nonDefaultDomain(name);
        DynamicMBean instance = getMBean(name);

	checkMBeanPermission(instance, null, name, "getObjectInstance");
        
        final String className = getClassName(instance);

	return new ObjectInstance(name, className);
    }

    public Set<ObjectInstance> queryMBeans(ObjectName name, QueryExp query) {
	SecurityManager sm = System.getSecurityManager();
	if (sm != null) {
	    // Check if the caller has the right to invoke 'queryMBeans'
	    //
	    checkMBeanPermission((String) null, null, null, "queryMBeans");

	    // Perform query without "query".
	    //
	    Set<ObjectInstance> list = queryMBeansImpl(name, null);

	    // Check if the caller has the right to invoke 'queryMBeans'
	    // on each specific classname/objectname in the list.
	    //
	    Set<ObjectInstance> allowedList =
		new HashSet<ObjectInstance>(list.size());
	    for (ObjectInstance oi : list) {
		try {
		    checkMBeanPermission(oi.getClassName(), null,
					 oi.getObjectName(), "queryMBeans");
		    allowedList.add(oi);
		} catch (SecurityException e) {
		    // OK: Do not add this ObjectInstance to the list
		}
	    }

	    // Apply query to allowed MBeans only.
	    //
            return filterListOfObjectInstances(allowedList, query);
	} else {
	    // Perform query.
	    //
	    return queryMBeansImpl(name, query);
	}
    }

    private Set<ObjectInstance> queryMBeansImpl(ObjectName name,
                                                QueryExp query) {
        // Query the MBeans on the repository
        //
        Set<NamedObject> list = null;
        synchronized(this) {
            list = repository.query(name, query);
        }

        if (queryByRepo) {
            // The repository performs the filtering
            query = null;
        }

        return (objectInstancesFromFilteredNamedObjects(list, query));
    }

    public Set<ObjectName> queryNames(ObjectName name, QueryExp query) {
	Set<ObjectName> queryList;
	SecurityManager sm = System.getSecurityManager();
	if (sm != null) {
	    // Check if the caller has the right to invoke 'queryNames'
	    //
	    checkMBeanPermission((String) null, null, null, "queryNames");

	    // Perform query without "query".
	    //
	    Set<ObjectInstance> list = queryMBeansImpl(name, null);

	    // Check if the caller has the right to invoke 'queryNames'
	    // on each specific classname/objectname in the list.
	    //
	    Set<ObjectInstance> allowedList =
		new HashSet<ObjectInstance>(list.size());
	    for (ObjectInstance oi : list) {
		try {
		    checkMBeanPermission(oi.getClassName(), null,
					 oi.getObjectName(), "queryNames");
		    allowedList.add(oi);
		} catch (SecurityException e) {
		    // OK: Do not add this ObjectInstance to the list
		}
	    }

	    // Apply query to allowed MBeans only.
	    //
            Set<ObjectInstance> queryObjectInstanceList =
                filterListOfObjectInstances(allowedList, query);
            queryList = new HashSet<ObjectName>(queryObjectInstanceList.size());
            for (ObjectInstance oi : queryObjectInstanceList) {
                queryList.add(oi.getObjectName());
            }
	} else {
	    // Perform query.
	    //
	    queryList = queryNamesImpl(name, query);
	}
	return queryList;
    }

    private Set<ObjectName> queryNamesImpl(ObjectName name, QueryExp query) {
        // Query the MBeans on the repository
        //
        Set<NamedObject> list = null;
        synchronized(this) {
            list = repository.query(name, query);
        }

        if (queryByRepo) {
            // The repository performs the filtering
            query = null;
        }

        return (objectNamesFromFilteredNamedObjects(list, query));
    }

    public boolean isRegistered(ObjectName name) {
        if (name == null) {
            throw new RuntimeOperationsException(
		     new IllegalArgumentException("Object name cannot be null"),
		     "Object name cannot be null");
        }

	name = nonDefaultDomain(name);

//  	/* Permission check */
//  	checkMBeanPermission(null, null, name, "isRegistered");

        synchronized(this) {
            return (repository.contains(name));
        }
    }

    public String[] getDomains()  {
	SecurityManager sm = System.getSecurityManager();
	if (sm != null) {
	    // Check if the caller has the right to invoke 'getDomains'
	    //
	    checkMBeanPermission((String) null, null, null, "getDomains");
	    
	    // Return domains
	    //
	    String[] domains = repository.getDomains();

	    // Check if the caller has the right to invoke 'getDomains'
	    // on each specific domain in the list.
	    //
	    List<String> result = new ArrayList<String>(domains.length);
	    for (int i = 0; i < domains.length; i++) {
		try {
		    ObjectName domain = new ObjectName(domains[i] + ":x=x");
		    checkMBeanPermission((String) null, null, domain, "getDomains");
		    result.add(domains[i]);
		} catch (MalformedObjectNameException e) {
		    // Should never occur... But let's log it just in case.
		    error("getDomains",
			  "Failed to check permission for domain=" + 
			  domains[i] + ". Error is: " + e);
		    debugX("getDomains",e);
		} catch (SecurityException e) {
		    // OK: Do not add this domain to the list
		}
	    }

	    // Make an array from result.
	    //
	    return result.toArray(new String[result.size()]);
	} else {
	    return repository.getDomains();
	}
    }

    public synchronized Integer getMBeanCount() {
        return (repository.getCount());
    }

    public Object getAttribute(ObjectName name, String attribute)
	throws MBeanException, AttributeNotFoundException,
	       InstanceNotFoundException, ReflectionException {

        if (name == null) {
            throw new RuntimeOperationsException(new
		IllegalArgumentException("Object name cannot be null"),
                "Exception occurred trying to invoke the getter on the MBean");
        }
        if (attribute == null) {
            throw new RuntimeOperationsException(new
		IllegalArgumentException("Attribute cannot be null"),
                "Exception occurred trying to invoke the getter on the MBean");
        }

	name = nonDefaultDomain(name);
	
        if (isTraceOn()) {
            trace("getAttribute", "Attribute= " + attribute +
		  ", obj= " + name);
        }

        final DynamicMBean instance = getMBean(name);
	checkMBeanPermission(instance, attribute, name, "getAttribute");

        try {
            return instance.getAttribute(attribute);
        } catch (AttributeNotFoundException e) {
            throw e;
        } catch (Throwable t) {
            rethrowMaybeMBeanException(t);
            throw new AssertionError(); // not reached
        }
    }

    public AttributeList getAttributes(ObjectName name, String[] attributes)
        throws InstanceNotFoundException, ReflectionException  {

        if (name == null) {
            throw new RuntimeOperationsException(new
		IllegalArgumentException("ObjectName name cannot be null"),
                "Exception occurred trying to invoke the getter on the MBean");
        }

        if (attributes == null) {
            throw new RuntimeOperationsException(new
		IllegalArgumentException("Attributes cannot be null"),
                "Exception occurred trying to invoke the getter on the MBean");
        }

	name = nonDefaultDomain(name);

        if (isTraceOn()) {
            trace("getAttributes", "Object= " + name);
        }

	final DynamicMBean instance = getMBean(name);
        final String[] allowedAttributes;
	final SecurityManager sm = System.getSecurityManager();
        if (sm == null)
            allowedAttributes = attributes;
        else {
	    final String classname = getClassName(instance);

	    // Check if the caller has the right to invoke 'getAttribute'
	    //
	    checkMBeanPermission(classname, null, name, "getAttribute");

	    // Check if the caller has the right to invoke 'getAttribute'
	    // on each specific attribute
	    //
	    List<String> allowedList =
		new ArrayList<String>(attributes.length);
	    for (String attr : attributes) {
		try {
		    checkMBeanPermission(classname, attr,
					 name, "getAttribute");
		    allowedList.add(attr);
		} catch (SecurityException e) {
		    // OK: Do not add this attribute to the list
		}
	    }
	    allowedAttributes = allowedList.toArray(new String[0]);
        }
        
        try {
	    return instance.getAttributes(allowedAttributes);
	} catch (Throwable t) {
            rethrow(t);
            throw new AssertionError();
        }
    }

    public void setAttribute(ObjectName name, Attribute attribute)
	throws InstanceNotFoundException, AttributeNotFoundException,
	       InvalidAttributeValueException, MBeanException,
	       ReflectionException  {

        if (name == null) {
            throw new RuntimeOperationsException(new
		IllegalArgumentException("ObjectName name cannot be null"),
                "Exception occurred trying to invoke the setter on the MBean");
        }

        if (attribute == null) {
            throw new RuntimeOperationsException(new
		IllegalArgumentException("Attribute cannot be null"),
                "Exception occurred trying to invoke the setter on the MBean");
        }

	name = nonDefaultDomain(name);

        if (isTraceOn()) {
            trace("setAttribute", "Object= " + name + ", attribute=" +
		  attribute.getName());
        }

        DynamicMBean instance = getMBean(name);
	checkMBeanPermission(instance, attribute.getName(),
			     name, "setAttribute");

        try {
            instance.setAttribute(attribute);
        } catch (AttributeNotFoundException e) {
            throw e;
        } catch (InvalidAttributeValueException e) {
            throw e;
        } catch (Throwable t) {
            rethrowMaybeMBeanException(t);
            throw new AssertionError();
        }
    }

    public AttributeList setAttributes(ObjectName name,
				       AttributeList attributes)
	    throws InstanceNotFoundException, ReflectionException  {

        if (name == null) {
            throw new RuntimeOperationsException(new
		IllegalArgumentException("ObjectName name cannot be null"),
		"Exception occurred trying to invoke the setter on the MBean");
        }

        if (attributes == null) {
            throw new RuntimeOperationsException(new
            IllegalArgumentException("AttributeList  cannot be null"),
	    "Exception occurred trying to invoke the setter on the MBean");
        }

	name = nonDefaultDomain(name);

	final DynamicMBean instance = getMBean(name);
        final AttributeList allowedAttributes;
	final SecurityManager sm = System.getSecurityManager();
        if (sm == null)
            allowedAttributes = attributes;
        else {
	    String classname = getClassName(instance);

	    // Check if the caller has the right to invoke 'setAttribute'
	    //
	    checkMBeanPermission(classname, null, name, "setAttribute");

	    // Check if the caller has the right to invoke 'setAttribute'
	    // on each specific attribute
	    //
	    allowedAttributes = new AttributeList(attributes.size());
	    for (Iterator i = attributes.iterator(); i.hasNext();) {
		try {
		    Attribute attribute = (Attribute) i.next();
		    checkMBeanPermission(classname, attribute.getName(),
					 name, "setAttribute");
		    allowedAttributes.add(attribute);
		} catch (SecurityException e) {
		    // OK: Do not add this attribute to the list
		}
	    }
        }
        try {
	    return instance.setAttributes(allowedAttributes);
        } catch (Throwable t) {
            rethrow(t);
            throw new AssertionError();
        }
    }

    public Object invoke(ObjectName name, String operationName,
			 Object params[], String signature[])
	    throws InstanceNotFoundException, MBeanException,
		   ReflectionException {

	name = nonDefaultDomain(name);

        DynamicMBean instance = getMBean(name);
	checkMBeanPermission(instance, operationName, name, "invoke");
        try {
            return instance.invoke(operationName, params, signature);
        } catch (Throwable t) {
            rethrowMaybeMBeanException(t);
            throw new AssertionError();
        }
    }

    /* Centralize some of the tedious exception wrapping demanded by the JMX
       spec. */
    private static void rethrow(Throwable t)
            throws ReflectionException {
        try {
            throw t;
        } catch (ReflectionException e) {
            throw e;
        } catch (RuntimeOperationsException e) {
            throw e;
        } catch (RuntimeErrorException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RuntimeMBeanException(e, e.toString());
        } catch (Error e) {
            throw new RuntimeErrorException(e, e.toString());
        } catch (Throwable t2) {
            // should not happen
            throw new RuntimeException("Unexpected exception", t2);
        }
    }
    
    private static void rethrowMaybeMBeanException(Throwable t)
            throws ReflectionException, MBeanException {
        if (t instanceof MBeanException)
            throw (MBeanException) t;
        rethrow(t);
    }

    /**
     * Register <code>object</code> in the repository, with the
     * given <code>name</code>.
     * This method is called by the various createMBean() flavours
     * and by registerMBean() after all MBean compliance tests
     * have been performed.
     * <p>
     * This method does not performed any kind of test compliance,
     * and the caller should make sure that the given <code>object</code>
     * is MBean compliant.
     * <p>
     * This methods performed all the basic steps needed for object
     * registration:
     * <ul>
     * <li>If the <code>object</code> implements the MBeanRegistration
     *     interface, it invokes preRegister() on the object.</li>
     * <li>Then the object is added to the repository with the given
     *     <code>name</code>.</li>
     * <li>Finally, if the <code>object</code> implements the
     *     MBeanRegistration interface, it invokes postRegister()
     *     on the object.</li>
     * </ul>
     * @param object A reference to a MBean compliant object.
     * @param name   The ObjectName of the <code>object</code> MBean.
     * @return the actual ObjectName with which the object was registered.
     * @exception InstanceAlreadyExistsException if an object is already
     *            registered with that name.
     * @exception MBeanRegistrationException if an exception occurs during
     *            registration.
     **/
    private ObjectInstance registerObject(String classname,
                                          Object object, ObjectName name)
	throws InstanceAlreadyExistsException, 
	       MBeanRegistrationException,
	       NotCompliantMBeanException {
      
        if (object == null) {
	    final RuntimeException wrapped =
		new IllegalArgumentException("Cannot add null object");
            throw new RuntimeOperationsException(wrapped,
                        "Exception occurred trying to register the MBean");
        }
        
        DynamicMBean mbean = Introspector.makeDynamicMBean(object);
        
        return registerDynamicMBean(classname, mbean, name);
    }
    
    private ObjectInstance registerDynamicMBean(String classname,
                                                DynamicMBean mbean,
                                                ObjectName name)
	throws InstanceAlreadyExistsException, 
	       MBeanRegistrationException,
	       NotCompliantMBeanException {
    

	name = nonDefaultDomain(name);

        if (isTraceOn()) {
            trace(dbgTag, "registerMBean", "ObjectName = " + name);
        }
	
	ObjectName logicalName = name;

        if (mbean instanceof MBeanRegistration) {
            MBeanRegistration reg = (MBeanRegistration) mbean;
            logicalName = preRegisterInvoke(reg, name, server);
            if (mbean instanceof DynamicMBean2) {
                try {
                    ((DynamicMBean2) mbean).preRegister2(server, logicalName);
                } catch (Exception e) {
                    postRegisterInvoke(reg, false, false);
                    if (e instanceof RuntimeException)
                        throw (RuntimeException) e;
                    if (e instanceof InstanceAlreadyExistsException)
                        throw (InstanceAlreadyExistsException) e;
                    throw new RuntimeException(e);
                }
            }
                    
	    if (logicalName != name && logicalName != null) {
		logicalName =
		    ObjectName.getInstance(nonDefaultDomain(logicalName));
	    }
        }

	checkMBeanPermission(classname, null, logicalName, "registerMBean");

	final ObjectInstance result;
        if (logicalName!=null) {
	    result = new ObjectInstance(logicalName, classname);
            internal_addObject(mbean, logicalName);
        } else {
            if (mbean instanceof MBeanRegistration)
                postRegisterInvoke((MBeanRegistration) mbean, false, true);
	    final RuntimeException wrapped =
		new IllegalArgumentException("No object name specified");
            throw new RuntimeOperationsException(wrapped,
                        "Exception occurred trying to register the MBean");
        }

        if (mbean instanceof MBeanRegistration)
            postRegisterInvoke((MBeanRegistration) mbean, true, false);

        /**
         * Checks if the newly registered MBean is a ClassLoader
	 * If so, tell the ClassLoaderRepository (CLR) about it.  We do
	 * this even if the object is a PrivateClassLoader.  In that
	 * case, the CLR remembers the loader for use when it is
	 * explicitly named (e.g. as the loader in createMBean) but
	 * does not add it to the list that is consulted by
	 * ClassLoaderRepository.loadClass.
         */
        final Object resource = getResource(mbean);
        if (resource instanceof ClassLoader) {
	    final ModifiableClassLoaderRepository clr =
		instantiator.getClassLoaderRepository();
	    if (clr == null) {
		final RuntimeException wrapped =
		    new IllegalArgumentException(
		     "Dynamic addition of class loaders is not supported");
		throw new RuntimeOperationsException(wrapped,
	   "Exception occurred trying to register the MBean as a class loader");
	    }
	    clr.addClassLoader(logicalName, (ClassLoader) resource);
        }

	return result;
    }

    private static ObjectName preRegisterInvoke(MBeanRegistration moi,
                                                ObjectName name,
                                                MBeanServer mbs)
            throws InstanceAlreadyExistsException, MBeanRegistrationException {

        final ObjectName newName;
      
        try {
            newName = moi.preRegister(mbs, name);
        } catch (RuntimeException e) {
                throw new RuntimeMBeanException((RuntimeException)e, 
			   "RuntimeException thrown in preRegister method");       
	} catch (Error er) {      
                throw new RuntimeErrorException((Error) er, 
                           "Error thrown in preRegister method");
	} catch (MBeanRegistrationException r) {
	    throw (MBeanRegistrationException)r;
	} catch (Exception ex) {
	    throw new MBeanRegistrationException((Exception) ex, 
			  "Exception thrown in preRegister method");
	}      
        
	if (newName != null) return newName;
	else return name;
    }

    private static void postRegisterInvoke(MBeanRegistration moi,
                                           boolean registrationDone,
                                           boolean registerFailed) {

        if (registerFailed && moi instanceof DynamicMBean2)
            ((DynamicMBean2) moi).registerFailed();
        try {
            moi.postRegister(new Boolean(registrationDone));
        } catch (RuntimeException e) {
	    throw new RuntimeMBeanException((RuntimeException)e,  
		      "RuntimeException thrown in postRegister method");   
	} catch (Error er) {
	    throw new RuntimeErrorException((Error) er,  
		      "Error thrown in postRegister method");       
	}
    }
   
    private static void preDeregisterInvoke(MBeanRegistration moi) 
            throws MBeanRegistrationException {
	try {
            moi.preDeregister();
        } catch (RuntimeException e) {
	    throw new RuntimeMBeanException((RuntimeException) e,  
                         "RuntimeException thrown in preDeregister method");
	} catch (Error er) {         
	    throw new RuntimeErrorException((Error) er,  
                         "Error thrown in preDeregister method");     
	} catch (MBeanRegistrationException t) {
	    throw (MBeanRegistrationException)t;   
	} catch (Exception ex) {
	    throw new MBeanRegistrationException((Exception)ex,  
                         "Exception thrown in preDeregister method"); 
	}
    }
   
    private static void postDeregisterInvoke(MBeanRegistration moi) {
        try {
            moi.postDeregister();
        } catch (RuntimeException e) {
	    throw new RuntimeMBeanException((RuntimeException)e, 
                         "RuntimeException thrown in postDeregister method"); 
	} catch (Error er) {
	    throw new RuntimeErrorException((Error) er, 
                         "Error thrown in postDeregister method"); 
	} 
    }
    
    /**
     * Gets a specific MBean controlled by the DefaultMBeanServerInterceptor.
     * The name must have a non-default domain.
     */
    private DynamicMBean getMBean(ObjectName name)
	throws InstanceNotFoundException {

        if (name == null) {
            throw new RuntimeOperationsException(new
		IllegalArgumentException("Object name cannot be null"),
			       "Exception occurred trying to get an MBean");
        }
        DynamicMBean obj = null;
        synchronized(this) {
            obj = repository.retrieve(name);
            if (obj == null) {
		if (isTraceOn()) {
		    trace("getMBean", name+": Found no object");
		}
		throw new InstanceNotFoundException(name.toString());
            }
        }
        return obj;
    }
    
    private static Object getResource(DynamicMBean mbean) {
        if (mbean instanceof DynamicMBean2)
            return ((DynamicMBean2) mbean).getResource();
        else
            return mbean;
    }

    private ObjectName nonDefaultDomain(ObjectName name) {
	if (name == null || name.getDomain().length() > 0)
	    return name;

	/* The ObjectName looks like ":a=b", and that's what its
	   toString() will return in this implementation.  So
	   we can just stick the default domain in front of it
	   to get a non-default-domain name.  We depend on the
	   fact that toString() works like that and that it
	   leaves wildcards in place (so we can detect an error
	   if one is supplied where it shouldn't be).  */
	final String completeName = domain + name;

	try {
	    return new ObjectName(completeName);
	} catch (MalformedObjectNameException e) {
	    final String msg =
		"Unexpected default domain problem: " + completeName + ": " +
		e;
	    throw EnvHelp.initCause(new IllegalArgumentException(msg), e);
	}
    }

    public String getDefaultDomain()  {
        return domain;
    }

    /*
     * Notification handling.
     *
     * This is not trivial, because the MBeanServer translates the
     * source of a received notification from a reference to an MBean
     * into the ObjectName of that MBean.  While that does make
     * notification sending easier for MBean writers, it comes at a
     * considerable cost.  We need to replace the source of a
     * notification, which is basically wrong if there are also
     * listeners registered directly with the MBean (without going
     * through the MBean server).  We also need to wrap the listener
     * supplied by the client of the MBeanServer with a listener that
     * performs the substitution before forwarding.  This is why we
     * strongly discourage people from putting MBean references in the
     * source of their notifications.  Instead they should arrange to
     * put the ObjectName there themselves.
     *
     * However, existing code relies on the substitution, so we are
     * stuck with it.
     *
     * Here's how we handle it.  When you add a listener, we make a
     * ListenerWrapper around it.  We look that up in the
     * listenerWrappers map, and if there was already a wrapper for
     * that listener with the given ObjectName, we reuse it.  This map
     * is a WeakHashMap, so a listener that is no longer registered
     * with any MBean can be garbage collected.
     *
     * We cannot use simpler solutions such as always creating a new
     * wrapper or always registering the same listener with the MBean
     * and using the handback to find the client's original listener.
     * The reason is that we need to support the removeListener
     * variant that removes all (listener,filter,handback) triples on
     * a broadcaster that have a given listener.  And we do not have
     * any way to inspect a broadcaster's internal list of triples.
     * So the same client listener must always map to the same
     * listener registered with the broadcaster.
     *
     * Another possible solution would be to map from ObjectName to
     * list of listener wrappers (or IdentityHashMap of listener
     * wrappers), making this list the first time a listener is added
     * on a given MBean, and removing it when the MBean is removed.
     * This is probably more costly in memory, but could be useful if
     * some day we don't want to rely on weak references.
     */
    public void addNotificationListener(ObjectName name,
					NotificationListener listener,
					NotificationFilter filter,
					Object handback)
	    throws InstanceNotFoundException {

	// ------------------------------
	// ------------------------------
        if (isTraceOn()) {
            trace("addNotificationListener", "obj= " + name);
        }

        DynamicMBean instance = getMBean(name);
	checkMBeanPermission(instance, null, name, "addNotificationListener");

        NotificationBroadcaster broadcaster =
                getNotificationBroadcaster(name, instance,
                                           NotificationBroadcaster.class);

        // ------------------
        // Check listener
        // ------------------
        if (listener == null) {
	    throw new RuntimeOperationsException(new
		IllegalArgumentException("Null listener"),"Null listener");
	}

	NotificationListener listenerWrapper =
	    getListenerWrapper(listener, name, broadcaster, true);
	broadcaster.addNotificationListener(listenerWrapper, filter, handback);
    }

    public void addNotificationListener(ObjectName name,
					ObjectName listener,
					NotificationFilter filter,
					Object handback)
	    throws InstanceNotFoundException {

	// ------------------------------
	// ------------------------------

        // ----------------
        // Get listener object
        // ----------------
        DynamicMBean instance = getMBean(listener);
        Object resource = getResource(instance);
        if (!(resource instanceof NotificationListener)) {
	    throw new RuntimeOperationsException(new
		IllegalArgumentException(listener.getCanonicalName()),
		"The MBean " + listener.getCanonicalName() +
		"does not implement the NotificationListener interface") ;
        }

        // ----------------
        // Add a listener on an MBean
        // ----------------
        if (isTraceOn()) {
            trace("addNotificationListener", "obj= " + name + " listener= " +
		  listener);
        }
        server.addNotificationListener(name,(NotificationListener) resource,
				       filter, handback) ;
    }

    public void removeNotificationListener(ObjectName name,
					   NotificationListener listener)
	    throws InstanceNotFoundException, ListenerNotFoundException {
	removeNotificationListener(name, listener, null, null, true);
    }

    public void removeNotificationListener(ObjectName name,
					   NotificationListener listener,
					   NotificationFilter filter,
					   Object handback)
	    throws InstanceNotFoundException, ListenerNotFoundException {
	removeNotificationListener(name, listener, filter, handback, false);
    }

    public void removeNotificationListener(ObjectName name,
					   ObjectName listener)
	    throws InstanceNotFoundException, ListenerNotFoundException {
	NotificationListener instance = getListener(listener);

        if (isTraceOn()) {
            trace("removeNotificationListener", "obj= " + name +
		  " listener= " + listener);
        }
	server.removeNotificationListener(name, instance);
    }

    public void removeNotificationListener(ObjectName name,
					   ObjectName listener,
					   NotificationFilter filter,
					   Object handback)
	    throws InstanceNotFoundException, ListenerNotFoundException {

	NotificationListener instance = getListener(listener);

        if (isTraceOn()) {
            trace("removeNotificationListener", "obj= " + name +
		  " listener= " + listener);
        }
	server.removeNotificationListener(name, instance, filter, handback);
    }

    private NotificationListener getListener(ObjectName listener) 
	throws ListenerNotFoundException {
        // ----------------
        // Get listener object
        // ----------------
        DynamicMBean instance;
        try {
	    instance = getMBean(listener);
	} catch (InstanceNotFoundException e) {
	    throw EnvHelp.initCause(
			  new ListenerNotFoundException(e.getMessage()), e);
	}

        Object resource = getResource(instance);
        if (!(resource instanceof NotificationListener)) {
	    final RuntimeException exc =
		new IllegalArgumentException(listener.getCanonicalName());
	    final String msg =
		"MBean " + listener.getCanonicalName() + " does not " +
		"implement " + NotificationListener.class.getName();
            throw new RuntimeOperationsException(exc, msg);
        }
	return (NotificationListener) resource;
    }

    private void removeNotificationListener(ObjectName name,
					    NotificationListener listener,
					    NotificationFilter filter,
					    Object handback,
					    boolean removeAll)
	    throws InstanceNotFoundException, ListenerNotFoundException {

        if (isTraceOn()) {
            trace("removeNotificationListener", "obj= " + name);
        }

        DynamicMBean instance = getMBean(name);
	checkMBeanPermission(instance, null, name,
			     "removeNotificationListener");
        Object resource = getResource(instance);

	/* We could simplify the code by assigning broadcaster after
	   assigning listenerWrapper, but that would change the error
	   behavior when both the broadcaster and the listener are
	   erroneous.  */
        
        Class<? extends NotificationBroadcaster> reqClass =
            removeAll ? NotificationBroadcaster.class : NotificationEmitter.class;
        NotificationBroadcaster broadcaster =
            getNotificationBroadcaster(name, instance, reqClass);

	NotificationListener listenerWrapper =
	    getListenerWrapper(listener, name, resource, false);

        if (listenerWrapper == null)
            throw new ListenerNotFoundException("Unknown listener");

	if (removeAll)
	    broadcaster.removeNotificationListener(listenerWrapper);
	else {
            NotificationEmitter emitter = (NotificationEmitter) broadcaster;
	    emitter.removeNotificationListener(listenerWrapper,
					       filter,
					       handback);
	}
    }

    private static <T extends NotificationBroadcaster>
            T getNotificationBroadcaster(ObjectName name, Object instance,
                                         Class<T> reqClass) {
        if (instance instanceof DynamicMBean2)
            instance = ((DynamicMBean2) instance).getResource();
        if (reqClass.isInstance(instance))
            return reqClass.cast(instance);
        final RuntimeException exc =
            new IllegalArgumentException(name.getCanonicalName());
        final String msg =
            "MBean " + name.getCanonicalName() + " does not " +
            "implement " + reqClass.getName();
        throw new RuntimeOperationsException(exc, msg);
    }

    public MBeanInfo getMBeanInfo(ObjectName name)
	throws InstanceNotFoundException, IntrospectionException,
	       ReflectionException {

	// ------------------------------
	// ------------------------------

        DynamicMBean moi = getMBean(name);
	final MBeanInfo mbi;
        try {
            mbi = moi.getMBeanInfo();
        } catch (RuntimeMBeanException e) {
            throw e;
        } catch (RuntimeErrorException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RuntimeMBeanException(e,
                    "getMBeanInfo threw RuntimeException");
        } catch (Error e) {
            throw new RuntimeErrorException(e, "getMBeanInfo threw Error");
        }
	if (mbi == null)
	    throw new JMRuntimeException("MBean " + name +
					 "has no MBeanInfo");

	checkMBeanPermission(mbi.getClassName(), null, name, "getMBeanInfo");

	return mbi;
    }

    public boolean isInstanceOf(ObjectName name, String className)
	throws InstanceNotFoundException {

        DynamicMBean instance = getMBean(name);
	checkMBeanPermission(instance, null, name, "isInstanceOf");
        
        try {
            if (instance instanceof DynamicMBean2) {
                Object resource = ((DynamicMBean2) instance).getResource();
                ClassLoader loader = resource.getClass().getClassLoader();
                Class<?> c = Class.forName(className, false, loader);
                return c.isInstance(resource);
            }
            
            final String cn = getClassName(instance);
            if (cn.equals(className))
                return true;
            final ClassLoader cl = instance.getClass().getClassLoader();
            
            final Class<?> classNameClass = Class.forName(className, false, cl);
            if (classNameClass.isInstance(instance))
                return true;
            
            final Class<?> instanceClass = Class.forName(cn, false, cl);
            return classNameClass.isAssignableFrom(instanceClass);
        } catch (Exception x) {
            /* Could be SecurityException or ClassNotFoundException */
            debugX("isInstanceOf",x);
            return false;
        }

    }

    /**
     * <p>Return the {@link java.lang.ClassLoader} that was used for
     * loading the class of the named MBean.
     * @param mbeanName The ObjectName of the MBean.
     * @return The ClassLoader used for that MBean.
     * @exception InstanceNotFoundException if the named MBean is not found.
     */
    public ClassLoader getClassLoaderFor(ObjectName mbeanName) 
	throws InstanceNotFoundException {

        DynamicMBean instance = getMBean(mbeanName);
	checkMBeanPermission(instance, null, mbeanName, "getClassLoaderFor");
        return getResource(instance).getClass().getClassLoader();
    }
   
    /**
     * <p>Return the named {@link java.lang.ClassLoader}.
     * @param loaderName The ObjectName of the ClassLoader.
     * @return The named ClassLoader.
     * @exception InstanceNotFoundException if the named ClassLoader
     * is not found.
     */
    public ClassLoader getClassLoader(ObjectName loaderName)
	    throws InstanceNotFoundException {

	if (loaderName == null) {
	    checkMBeanPermission((String) null, null, null, "getClassLoader");
	    return server.getClass().getClassLoader();
	}

        DynamicMBean instance = getMBean(loaderName);
	checkMBeanPermission(instance, null, loaderName, "getClassLoader");
        
        Object resource = getResource(instance);

        /* Check if the given MBean is a ClassLoader */
	if (!(resource instanceof ClassLoader))
	    throw new InstanceNotFoundException(loaderName.toString() +
                                                " is not a classloader");

	return (ClassLoader) resource;
    }

    /**
     * Adds a MBean in the repository
     */
    private void internal_addObject(DynamicMBean object, ObjectName logicalName)
	throws InstanceAlreadyExistsException {

	// ------------------------------
	// ------------------------------

        // Let the repository do the work.

        try {
            synchronized(this) {
                repository.addMBean(object, logicalName);
            }
        }  catch (InstanceAlreadyExistsException e) {
            if (object instanceof MBeanRegistration) {
                postRegisterInvoke((MBeanRegistration) object, false, true);
            }
            throw e;
        }
        
        // ---------------------
        // Send create event
        // ---------------------
        if (isTraceOn()) {
            trace("addObject", "Send create notification of object " +
		  logicalName.getCanonicalName());
        }

        sendNotification(MBeanServerNotification.REGISTRATION_NOTIFICATION,
			 logicalName ) ;
    }

    /**
     * Sends an MBeanServerNotifications with the specified type for the
     * MBean with the specified ObjectName
     */
    private void sendNotification(String NotifType, ObjectName name) {

	// ------------------------------
	// ------------------------------

        // ---------------------
        // Create notification
        // ---------------------
	MBeanServerNotification notif = new MBeanServerNotification(
            NotifType,MBeanServerDelegate.DELEGATE_NAME,0,name);

	if (isTraceOn()) {
	    trace("sendNotification", NotifType + " " + name);
	}

	delegate.sendNotification(notif);
    }

    /**
     * Applies the specified queries to the set of NamedObjects.
     */
    private Set<ObjectName>
        objectNamesFromFilteredNamedObjects(Set<NamedObject> list,
                                            QueryExp query) {
        Set<ObjectName> result = new HashSet<ObjectName>();
        // No query ...
        if (query == null) {
            for (NamedObject no : list) {
                result.add(no.getName());
            }
        } else {
            // Access the filter
            MBeanServer oldServer = QueryEval.getMBeanServer();
            query.setMBeanServer(server);
            try {
                for (NamedObject no : list) {
                    final DynamicMBean obj = no.getObject();
                    boolean res;
                    try {
                        res = query.apply(no.getName());
                    } catch (Exception e) {
                        res = false;
                    }
                    if (res) {
                        result.add(no.getName());
                    }
                }
            } finally {
                /*
                 * query.setMBeanServer is probably
                 * QueryEval.setMBeanServer so put back the old
                 * value.  Since that method uses a ThreadLocal
                 * variable, this code is only needed for the
                 * unusual case where the user creates a custom
                 * QueryExp that calls a nested query on another
                 * MBeanServer.
                 */
                query.setMBeanServer(oldServer);
            }
        }
        return result;
    }

    /**
     * Applies the specified queries to the set of NamedObjects.
     */
    private Set<ObjectInstance>
        objectInstancesFromFilteredNamedObjects(Set<NamedObject> list,
                                                QueryExp query) {
        Set<ObjectInstance> result = new HashSet<ObjectInstance>();
        // No query ...
        if (query == null) {
            for (NamedObject no : list) {
                final DynamicMBean obj = no.getObject();
                final String className = safeGetClassName(obj);
                result.add(new ObjectInstance(no.getName(), className));
            }
        } else {
            // Access the filter
            MBeanServer oldServer = QueryEval.getMBeanServer();
            query.setMBeanServer(server);
            try {
                for (NamedObject no : list) {
                    final DynamicMBean obj = no.getObject();
                    boolean res;
                    try {
                        res = query.apply(no.getName());
                    } catch (Exception e) {
                        res = false;
                    }
                    if (res) {
                        String className = safeGetClassName(obj);
                        result.add(new ObjectInstance(no.getName(), className));
                    }
                }
            } finally {
                /*
                 * query.setMBeanServer is probably
                 * QueryEval.setMBeanServer so put back the old
                 * value.  Since that method uses a ThreadLocal
                 * variable, this code is only needed for the
                 * unusual case where the user creates a custom
                 * QueryExp that calls a nested query on another
                 * MBeanServer.
                 */
                query.setMBeanServer(oldServer);
            }
        }
        return result;
    }

    private static String safeGetClassName(DynamicMBean mbean) {
        try {
            return getClassName(mbean);
        } catch (Exception e) {
            debugX("Exception getting MBean class name", e);
            return null;
        }
    }

    /**
     * Applies the specified queries to the set of ObjectInstances.
     */
    private Set<ObjectInstance>
	    filterListOfObjectInstances(Set<ObjectInstance> list,
					QueryExp query) {
        // Null query.
	//
        if (query == null) {
	    return list;
        } else {
	    Set<ObjectInstance> result = new HashSet<ObjectInstance>();
            // Access the filter.
	    //
	    for (ObjectInstance oi : list) {
                boolean res = false;
		MBeanServer oldServer = QueryEval.getMBeanServer();
		query.setMBeanServer(server);
                try {
                    res = query.apply(oi.getObjectName());
                } catch (Exception e) {
                    res = false;
                } finally {
		    /*
		     * query.setMBeanServer is probably
		     * QueryEval.setMBeanServer so put back the old
		     * value.  Since that method uses a ThreadLocal
		     * variable, this code is only needed for the
		     * unusual case where the user creates a custom
		     * QueryExp that calls a nested query on another
		     * MBeanServer.
		     */
		    query.setMBeanServer(oldServer);
		}
                if (res) {
		    result.add(oi);
                }
            }
	    return result;
        }
    }

    /*
     * Get the existing wrapper for this listener, name, and mbean, if
     * there is one.  Otherwise, if "create" is true, create and
     * return one.  Otherwise, return null.
     *
     * We use a WeakHashMap so that if the only reference to a user
     * listener is in listenerWrappers, it can be garbage collected.
     * This requires a certain amount of care, because only the key in
     * a WeakHashMap is weak; the value is strong.  We need to recover
     * the existing wrapper object (not just an object that is equal
     * to it), so we would like listenerWrappers to map any
     * ListenerWrapper to the canonical ListenerWrapper for that
     * (listener,name,mbean) set.  But we do not want this canonical
     * wrapper to be referenced strongly.  Therefore we put it inside
     * a WeakReference and that is the value in the WeakHashMap.
     */
    private NotificationListener getListenerWrapper(NotificationListener l,
						    ObjectName name,
						    Object mbean,
						    boolean create) {
	ListenerWrapper wrapper = new ListenerWrapper(l, name, mbean);
	synchronized (listenerWrappers) {
	    WeakReference<ListenerWrapper> ref = listenerWrappers.get(wrapper);
	    if (ref != null) {
		NotificationListener existing = ref.get();
		if (existing != null)
		    return existing;
	    }
	    if (create) {
		ref = new WeakReference<ListenerWrapper>(wrapper);
		listenerWrappers.put(wrapper, ref);
		return wrapper;
	    } else
		return null;
	}
    }

    private static class ListenerWrapper implements NotificationListener {
	ListenerWrapper(NotificationListener l, ObjectName name,
			Object mbean) {
	    this.listener = l;
	    this.name = name;
	    this.mbean = mbean;
	}

	public void handleNotification(Notification notification,
				       Object handback) {
	    if (notification != null) {
		if (notification.getSource() == mbean)
		    notification.setSource(name);
	    }

	    /*
	     * Listeners are not supposed to throw exceptions.  If
	     * this one does, we could remove it from the MBean.  It
	     * might indicate that a connector has stopped working,
	     * for instance, and there is no point in sending future
	     * notifications over that connection.  However, this
	     * seems rather drastic, so instead we propagate the
	     * exception and let the broadcaster handle it.
	     */
	    listener.handleNotification(notification, handback);
	}

	public boolean equals(Object o) {
	    if (!(o instanceof ListenerWrapper))
		return false;
	    ListenerWrapper w = (ListenerWrapper) o;
	    return (w.listener == listener && w.mbean == mbean
		    && w.name.equals(name));
	    /*
	     * We compare all three, in case the same MBean object
	     * gets unregistered and then reregistered under a
	     * different name, or the same name gets assigned to two
	     * different MBean objects at different times.  We do the
	     * comparisons in this order to avoid the slow
	     * ObjectName.equals when possible.
	     */
	}

	public int hashCode() {
	    return (System.identityHashCode(listener) ^
		    System.identityHashCode(mbean));
	    /*
	     * We do not include name.hashCode() in the hash because
	     * computing it is slow and usually we will not have two
	     * instances of ListenerWrapper with the same mbean but
	     * different ObjectNames.  That can happen if the MBean is
	     * unregistered from one name and reregistered with
	     * another, and there is no garbage collection between; or
	     * if the same object is registered under two names (which
	     * is not recommended because MBeanRegistration will
	     * break).  But even in these unusual cases the hash code
	     * does not have to be unique.
	     */
	}

	private NotificationListener listener;
	private ObjectName name;
	private Object mbean;
    }

    // SECURITY CHECKS
    //----------------

    private static String getClassName(DynamicMBean mbean) {
        if (mbean instanceof DynamicMBean2)
            return ((DynamicMBean2) mbean).getClassName();
        else
            return mbean.getMBeanInfo().getClassName();
    }

    private static void checkMBeanPermission(DynamicMBean mbean,
                                             String member,
                                             ObjectName objectName,
                                             String actions) {
	SecurityManager sm = System.getSecurityManager();
	if (sm != null) {
            checkMBeanPermission(safeGetClassName(mbean),
                                 member,
                                 objectName,
                                 actions);
        }
    }

    private static void checkMBeanPermission(String classname,
					     String member,
					     ObjectName objectName,
					     String actions) {
	SecurityManager sm = System.getSecurityManager();
	if (sm != null) {
	    Permission perm = new MBeanPermission(classname,
						  member,
						  objectName,
						  actions);
	    sm.checkPermission(perm);
	}
    }

    private static void checkMBeanTrustPermission(final Class theClass)
	throws SecurityException {
	SecurityManager sm = System.getSecurityManager();
	if (sm != null) {
	    Permission perm = new MBeanTrustPermission("register");
	    PrivilegedAction<ProtectionDomain> act =
		new PrivilegedAction<ProtectionDomain>() {
		    public ProtectionDomain run() {
			return theClass.getProtectionDomain();
		    }
		};
	    ProtectionDomain pd = AccessController.doPrivileged(act);
	    AccessControlContext acc =
		new AccessControlContext(new ProtectionDomain[] { pd });
	    sm.checkPermission(perm, acc);
	}
    }

    // TRACES & DEBUG
    //---------------

    private static boolean isTraceOn() {
        return Trace.isSelected(Trace.LEVEL_TRACE, Trace.INFO_MBEANSERVER);
    }

    private static void trace(String clz, String func, String info) {
        Trace.send(Trace.LEVEL_TRACE, Trace.INFO_MBEANSERVER, clz, func, info);
    }

    private static void trace(String func, String info) {
        trace(dbgTag, func, info);
    }

    private static void error(String func, String info) {
        Trace.send(Trace.LEVEL_ERROR,Trace.INFO_MBEANSERVER,dbgTag,func,info);
    }

    private static boolean isDebugOn() {
        return Trace.isSelected(Trace.LEVEL_DEBUG, Trace.INFO_MBEANSERVER);
    }

    private static void debug(String clz, String func, String info) {
        Trace.send(Trace.LEVEL_DEBUG, Trace.INFO_MBEANSERVER, clz, func, info);
    }

    private static void debug(String func, String info) {
        debug(dbgTag, func, info);
    }

    private static void debugX(String func,Throwable e) {
	if (isDebugOn()) {
	    final StringWriter s = new StringWriter();
	    e.printStackTrace(new PrintWriter(s));
	    final String stack = s.toString();

	    debug(dbgTag,func,"Exception caught in "+ func+"(): "+e);
	    debug(dbgTag,func,stack);

	    // java.lang.System.err.println("**** Exception caught in "+
	    //				 func+"(): "+e);
	    // java.lang.System.err.println(stack);
	}
    }
}
