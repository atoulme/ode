/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.ode.daohib.bpel;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ode.bpel.common.CorrelationKey;
import org.apache.ode.bpel.dao.CorrelatorDAO;
import org.apache.ode.bpel.dao.DeferredProcessInstanceCleanable;
import org.apache.ode.bpel.dao.ProcessDAO;
import org.apache.ode.bpel.dao.ProcessInstanceDAO;
import org.apache.ode.bpel.iapi.ProcessConf.CLEANUP_CATEGORY;
import org.apache.ode.daohib.SessionManager;
import org.apache.ode.daohib.bpel.hobj.HActivityRecovery;
import org.apache.ode.daohib.bpel.hobj.HBpelEvent;
import org.apache.ode.daohib.bpel.hobj.HCorrelationProperty;
import org.apache.ode.daohib.bpel.hobj.HCorrelationSet;
import org.apache.ode.daohib.bpel.hobj.HCorrelator;
import org.apache.ode.daohib.bpel.hobj.HCorrelatorMessage;
import org.apache.ode.daohib.bpel.hobj.HCorrelatorSelector;
import org.apache.ode.daohib.bpel.hobj.HFaultData;
import org.apache.ode.daohib.bpel.hobj.HLargeData;
import org.apache.ode.daohib.bpel.hobj.HMessage;
import org.apache.ode.daohib.bpel.hobj.HMessageExchange;
import org.apache.ode.daohib.bpel.hobj.HMessageExchangeProperty;
import org.apache.ode.daohib.bpel.hobj.HObject;
import org.apache.ode.daohib.bpel.hobj.HPartnerLink;
import org.apache.ode.daohib.bpel.hobj.HProcess;
import org.apache.ode.daohib.bpel.hobj.HProcessInstance;
import org.apache.ode.daohib.bpel.hobj.HScope;
import org.apache.ode.daohib.bpel.hobj.HVariableProperty;
import org.apache.ode.daohib.bpel.hobj.HXmlData;
import org.hibernate.Criteria;
import org.hibernate.Hibernate;
import org.hibernate.HibernateException;
import org.hibernate.LockMode;
import org.hibernate.Query;
import org.hibernate.criterion.Expression;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;

import javax.xml.namespace.QName;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.Set;

/**
 * Hibernate-based {@link ProcessDAO} implementation.
 */
public class ProcessDaoImpl extends HibernateDao implements ProcessDAO, DeferredProcessInstanceCleanable {
    private static final Log __log = LogFactory.getLog(ProcessDaoImpl.class);
    
    private static final String QRY_CORRELATOR = "where this.correlatorId = ?";

    private HProcess _process;

    public ProcessDaoImpl(SessionManager sm, HProcess process) {
        super(sm,process);
        entering("ProcessDaoImpl.ProcessDaoImpl");
        _process = process;
    }

    public QName getProcessId() {
        return QName.valueOf(_process.getProcessId());
    }

    public ProcessInstanceDAO getInstance(Long iid) {
        entering("ProcessDaoImpl.getInstance");
        ProcessInstanceDAO instance = BpelDAOConnectionImpl._getInstance(_sm, getSession(), iid);
        if (instance == null || !instance.getProcess().getProcessId().equals(getProcessId()))
            return null;
        return instance;
    }

    @SuppressWarnings("unchecked")
    public CorrelatorDAO getCorrelator(String  corrId) {
        entering("ProcessDaoImpl.getCorrelator");
        Iterator results;
        Query q = getSession().createFilter(_process.getCorrelators(),
                QRY_CORRELATOR);
        results = q.setString(0, corrId).iterate();

        if(!results.hasNext()){
            String msg = "no such correlator: corrId = " + corrId;
            throw new IllegalArgumentException(msg);
        }
        try {
            return new CorrelatorDaoImpl(_sm, (HCorrelator)results.next());
        } finally {
            Hibernate.close(results);
        }
    }

    public void removeRoutes(String routeId, ProcessInstanceDAO target) {
        entering("ProcessDaoImpl.removeRoutes");
        for (HCorrelator hCorrelator : _process.getCorrelators()) {
            new CorrelatorDaoImpl(_sm, hCorrelator).removeRoutes(routeId, target);
        }
    }

    public ProcessInstanceDAO createInstance(CorrelatorDAO correlator) {
        entering("ProcessDaoImpl.createInstance");
        HProcessInstance instance = new HProcessInstance();
        if (correlator != null)
            instance.setInstantiatingCorrelator((HCorrelator)((CorrelatorDaoImpl)correlator).getHibernateObj());
        instance.setProcess(_process);
        instance.setCreated(new Date());
        getSession().save(instance);
//        _process.addInstance(instance);

        return new ProcessInstanceDaoImpl(_sm,instance);
    }

    public Collection<ProcessInstanceDAO> findInstance(CorrelationKey key) {
    	return findInstance(key, true);
    }
    
    /**
     * @see org.apache.ode.bpel.dao.ProcessDAO#findInstance(CorrelationKey)
     */
    @SuppressWarnings("unchecked")
    public Collection<ProcessInstanceDAO> findInstance(CorrelationKey ckeyValue, boolean wait) {
    	try {
	        entering("ProcessDaoImpl.findInstance");
	        Criteria correlationSet = getSession().createCriteria(HCorrelationSet.class);
	        Criteria instance = correlationSet.createCriteria("scope").createCriteria("instance");
	        instance.addOrder(Order.desc("created"));
	        Criteria process = instance.createCriteria("process");
	        process.add(Restrictions.eq("id", _process.getId()));
	        correlationSet.add(Expression.eq("value", ckeyValue.toCanonicalString()));
	        // TODO: compare against the correlation set in question  
	        // correlationSet.add(Expression.eq("name", ckeyValue.getCSetId()));	        
	        correlationSet.setLockMode(wait ? LockMode.UPGRADE : LockMode.UPGRADE_NOWAIT);
	        return correlationSet.list();
    	} catch (HibernateException he) {
    		return Collections.EMPTY_LIST;
    	}
    }

    /**
     * @see org.apache.ode.bpel.dao.ProcessDAO#instanceCompleted(ProcessInstanceDAO)
     */
    public void instanceCompleted(ProcessInstanceDAO instance) {
        // nothing to do here (yet?)
    }

    public void deleteProcessAndRoutes() {
        entering("ProcessDaoImpl.delete");

        // delete routes
        getSession().getNamedQuery(HCorrelatorSelector.DELETE_MESSAGE_ROUTES_BY_PROCESS).setParameter("process", _process).executeUpdate();

        // delete process dao
        getSession().getNamedQuery(HCorrelator.DELETE_CORRELATORS_BY_PROCESS).setParameter("process", _process).executeUpdate();
        getSession().delete(_process); // this deletes HCorrelator -> HCorrelatorSelector

        // after this delete, we have a use case that creates the process with the same procid.
        // for hibernate to work without the database deferred constraint check, let's just flush the session.
        getSession().flush();
    }

    @SuppressWarnings("unchecked")
    public int deleteInstances(int transactionSize) {
        entering("ProcessDaoImpl.delete");

        if( transactionSize < 1 ) {
               if(__log.isWarnEnabled()) __log.warn("A zero or negative value was given for the transaction size of process dao deletion; overriding to '1'. Not using bulk deletion of rows may result in performance degradation.");
               transactionSize = 1;
        }

        Collection<HProcessInstance> instances = getSession().getNamedQuery(HProcessInstance.SELECT_INSTANCES_BY_PROCESS).setParameter("process", _process).setMaxResults(transactionSize).list();
        if( !instances.isEmpty() ) {
            deleteEvents(instances);
            deleteCorrelations(instances);
            deleteMessages(instances);
            deleteVariables(instances);
            deleteProcessInstances(instances);
        }

        return instances.size();
    }

    public int deleteInstances(Collection<HProcessInstance> instances, Set<CLEANUP_CATEGORY> categories) {
        entering("ProcessDaoImpl.deleteInstances");

        if( !instances.isEmpty() ) {
            if( categories.contains(CLEANUP_CATEGORY.EVENTS)) {
                deleteEvents(instances);
            }
            if( categories.contains(CLEANUP_CATEGORY.CORRELATIONS)) {
                deleteCorrelations(instances);
            }
            if( categories.contains(CLEANUP_CATEGORY.MESSAGES)) {
                deleteMessages(instances);
            }
            if( categories.contains(CLEANUP_CATEGORY.VARIABLES)) {
                deleteVariables(instances);
            }
            if( categories.contains(CLEANUP_CATEGORY.INSTANCE)) {
                deleteProcessInstances(instances);
            }
        }

        return instances.size();
    }
    
    private void deleteProcessInstances(Collection<HProcessInstance> instances) {
        getSession().getNamedQuery(HLargeData.DELETE_ACTIVITY_RECOVERY_LDATA_BY_INSTANCES).setParameterList("instances", instances).executeUpdate();
        getSession().getNamedQuery(HActivityRecovery.DELETE_ACTIVITY_RECOVERIES_BY_INSTANCES).setParameterList("instances", instances).executeUpdate();
        getSession().getNamedQuery(HLargeData.DELETE_FAULT_LDATA_BY_INSTANCE_IDS).setParameterList("instanceIds", HObject.toIdArray(instances)).executeUpdate();
        getSession().getNamedQuery(HFaultData.DELETE_FAULTS_BY_INSTANCES).setParameterList("instances", instances).executeUpdate();
        getSession().getNamedQuery(HLargeData.DELETE_JACOB_LDATA_BY_INSTANCES).setParameterList("instances", instances).executeUpdate();
        getSession().getNamedQuery(HProcessInstance.DELETE_INSTANCES).setParameterList("instances", instances).executeUpdate();
    }

    private void deleteVariables(Collection<HProcessInstance> instances) {
        getSession().getNamedQuery(HVariableProperty.DELETE_VARIABLE_PROPERITES_BY_INSTANCES).setParameterList("instances", instances).executeUpdate();
        getSession().getNamedQuery(HLargeData.DELETE_XMLDATA_LDATA_BY_INSTANCES).setParameterList("instances", instances).executeUpdate();
        getSession().getNamedQuery(HXmlData.DELETE_XMLDATA_BY_INSTANCES).setParameterList("instances", instances).executeUpdate();

        getSession().getNamedQuery(HLargeData.DELETE_PARTNER_LINK_LDATA_BY_INSTANCES).setParameterList("instances", instances).executeUpdate();
        getSession().getNamedQuery(HPartnerLink.DELETE_PARTNER_LINKS_BY_INSTANCES).setParameterList("instances", instances).executeUpdate();
        getSession().getNamedQuery(HScope.DELETE_SCOPES_BY_INSTANCES).setParameterList("instances", instances).executeUpdate();
    }

    private void deleteMessages(Collection<HProcessInstance> instances) {
        getSession().getNamedQuery(HCorrelatorMessage.DELETE_CORMESSAGES_BY_INSTANCES).setParameterList("instances", instances).executeUpdate();

        getSession().getNamedQuery(HLargeData.DELETE_MESSAGE_LDATA_BY_INSTANCES).setParameterList("instances", instances).executeUpdate();
        getSession().getNamedQuery(HMessage.DELETE_MESSAGES_BY_INSTANCES).setParameterList("instances", instances).executeUpdate();
        getSession().getNamedQuery(HMessageExchangeProperty.DELETE_MEX_PROPS_BY_INSTANCES).setParameterList("instances", instances).executeUpdate();
        getSession().getNamedQuery(HLargeData.DELETE_MEX_LDATA_BY_INSTANCES).setParameterList("instances", instances).executeUpdate();
        getSession().getNamedQuery(HMessageExchange.DELETE_MEX_BY_INSTANCES).setParameterList("instances", instances).executeUpdate();
    }

    private void deleteCorrelations(Collection<HProcessInstance> instances) {
        getSession().getNamedQuery(HCorrelationProperty.DELETE_CORPROPS_BY_INSTANCES).setParameterList ("instances", instances).executeUpdate();
        getSession().getNamedQuery(HCorrelationSet.DELETE_CORSETS_BY_INSTANCES).setParameterList ("instances", instances).executeUpdate();
    }

    private void deleteEvents(Collection<HProcessInstance> instances) {
        getSession().getNamedQuery(HLargeData.DELETE_EVENT_LDATA_BY_INSTANCES).setParameterList("instances", instances).executeUpdate();
        getSession().getNamedQuery(HBpelEvent.DELETE_EVENTS_BY_INSTANCES).setParameterList("instances", instances).executeUpdate();
    }

    public QName getType() {
        return new QName(_process.getTypeNamespace(), _process.getTypeName());
    }

    public long getVersion() {
        return _process.getVersion();
    }

    public CorrelatorDAO addCorrelator(String corrid) {
        entering("ProcessDaoImpl.addCorrelator");
        HCorrelator correlator = new HCorrelator();
        correlator.setCorrelatorId(corrid);
        correlator.setProcess(_process);
        correlator.setCreated(new Date());
//        _process.addCorrelator(correlator);
        getSession().save(correlator);
        getSession().saveOrUpdate(_process);
        return new CorrelatorDaoImpl(_sm, correlator);
    }

	public int getNumInstances() {
        entering("ProcessDaoImpl.getNumInstances");
        // this should be efficient if the relation is tagged as extra-lazy.
        // If the collection is not initialized yet, Hibernate will do a count(*) and the whole collection will not be fetched.
		return _process.getInstances().size();
	}

    public String getGuid() {
        return _process.getGuid();
    }

}
