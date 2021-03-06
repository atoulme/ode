package org.apache.ode.bpel.engine;

import org.apache.ode.bpel.iapi.*;
import org.apache.ode.bpel.iapi.Scheduler.JobDetails;
import org.apache.ode.bpel.rapi.ResourceModel;
import org.apache.ode.bpel.dao.MessageExchangeDAO;
import org.apache.ode.bpel.dao.ProcessInstanceDAO;
import org.apache.ode.bpel.dao.ResourceRouteDAO;
import org.apache.ode.bpel.common.FaultException;
import org.apache.ode.bpel.rapi.InvalidProcessException;
import org.apache.ode.bpel.evt.NewProcessInstanceEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;

public class ODERESTProcess extends ODEProcess {

    private ConcurrentHashMap<ResourceModel,String> _staticResources = new ConcurrentHashMap<ResourceModel,String>();

    private ArrayList<Resource> _instantiatingResources = new ArrayList<Resource>();

    public ODERESTProcess(BpelServerImpl server, ProcessConf conf, BpelEventListener debugger, IncomingMessageExchangeCache mexCache) {
        super(server, conf, debugger, mexCache);
        _processModel = conf.getProcessModel();
        _runtime = buildRuntime(_processModel.getModelVersion());
        _runtime.init(_pconf, _processModel);
    }

    public Collection<String> initResources() {
        if (_staticResources.size() > 0 ) return _staticResources.values();

        // Caching instantiating resource urls as those can be expressions
        ArrayList<String> addresses = new ArrayList<String>();
        for (ResourceModel resourceModel : _processModel.getProvidedResources()) {
            if (resourceModel.isInstantiateResource()) {
                try {
                    String addr = _runtime.extractAddress(resourceModel);
                    addresses.add(addr);
                    _staticResources.put(resourceModel, addr);
                } catch (FaultException e) {
                    throw new BpelEngineException(e);
                }
            }
        }
        return addresses;
    }

    void activate(Contexts contexts) {
        _contexts = contexts;
        bounceProcessDAO();
        // Resources to activate are the instantiating ones plus the waiting routes.

        List<ResourceRouteDAO> rrDaos;
        try {
            rrDaos = _contexts.execTransaction(new Callable<List<ResourceRouteDAO>>() {
                public List<ResourceRouteDAO> call() throws Exception {
                    return _contexts.dao.getConnection().getAllResourceRoutes();
                }
            });
        } catch (Exception ex) {
            String errmsg = "DbError";
            __log.error(errmsg, ex);
            throw new BpelEngineException(errmsg, ex);
        }

        for (ResourceRouteDAO rrDao : rrDaos) {
            Resource resource = new Resource(rrDao.getUrl(), "application/xml", rrDao.getMethod());
            _contexts.bindingContext.activateProvidedResource(resource);
            _instantiatingResources.add(resource);
        }

        // Activating instantiating resources
        for (ResourceModel resourceModel : _staticResources.keySet()) {
            Resource resource = new Resource(_staticResources.get(resourceModel),
                    "application/xml", resourceModel.getMethod());
            _contexts.bindingContext.activateProvidedResource(resource);
            _instantiatingResources.add(resource);
        }
    }

    void deactivate() {
        for (ResourceModel resourceModel : _staticResources.keySet()) {
            Resource resource = new Resource(_staticResources.get(resourceModel),
                    "application/xml", resourceModel.getMethod());
            _contexts.bindingContext.deactivateProvidedResource(resource);
        }
    }

    void invokeProcess(final MessageExchangeDAO mexdao) {
        if (_pconf.getState() == ProcessState.RETIRED) {
            throw new InvalidProcessException("Process is retired.", InvalidProcessException.RETIRED_CAUSE_CODE);
        }
        mexdao.setProcess(getProcessDAO());

        try {
            boolean instantiatingResource = isInstantiating(mexdao.getResource());
            InvocationStyle istyle = mexdao.getInvocationStyle();

            if (instantiatingResource) {
                ProcessInstanceDAO newInstance = getProcessDAO().createInstance(null);
                newInstance.setInstantiatingUrl(mexdao.getResource());

                // send process instance event
                NewProcessInstanceEvent evt = new NewProcessInstanceEvent(getProcessModel().getQName(),
                        getProcessDAO().getProcessId(), newInstance.getInstanceId());
                evt.setMexId(mexdao.getMessageExchangeId());
                saveEvent(evt, newInstance);

                mexdao.setCorrelationStatus(MyRoleMessageExchange.CorrelationStatus.CREATE_INSTANCE.toString());
                mexdao.setInstance(newInstance);

                doInstanceWork(mexdao.getInstance().getInstanceId(), new Callable<Void>() {
                    public Void call() {
                        executeCreateInstance(mexdao);
                        return null;
                    }
                });
            } else {
                // TODO avoid reloading the resource routing, it's just been loaded by the server on mex creation
                String[] urlMeth = mexdao.getResource().split("~");
                ResourceRouteDAO rr = _contexts.dao.getConnection().getResourceRoute(urlMeth[0], urlMeth[1]);
                if (rr == null) throw new BpelEngineException("NoSuchResource: " + mexdao.getResource());

                mexdao.setInstance(rr.getInstance());
                mexdao.setChannel(rr.getPickResponseChannel() + "&" + rr.getSelectorIdx());

                if (istyle == InvocationStyle.TRANSACTED) {
                    doInstanceWork(mexdao.getInstance().getInstanceId(), new Callable<Void>() {
                        public Void call() {
                            executeContinueInstanceMyRoleRequestReceived(mexdao);
                            return null;
                        }
                    });
                } else /* non-transacted style */ {
                    JobDetails j = new JobDetails();
                    j.setType(Scheduler.JobType.MYROLE_INVOKE);
                    j.setInstanceId(mexdao.getInstance().getInstanceId());
                    j.setMexId(mexdao.getMessageExchangeId());
                    // Could be different to this pid when routing to an older version
                    j.setProcessId(mexdao.getInstance().getProcess().getProcessId());

                    scheduleJob(j, null);
                }
                // Cleaning up
                _contexts.dao.getConnection().deleteResourceRoute(urlMeth[0], urlMeth[1]);
            }
        } finally {
            // If we did not get an ACK during this method, then mark this MEX as needing an ASYNC wake-up
            if (mexdao.getStatus() != MessageExchange.Status.ACK) mexdao.setStatus(MessageExchange.Status.ASYNC);
        }
    }

    void invokePartner(MessageExchangeDAO mexdao) {
        // TODO p2p short-circuiting
        mexdao.setStatus(MessageExchange.Status.REQ);
        try {
            String[] resStr = mexdao.getResource().split("~");
            RESTOutMessageExchangeImpl outMex = new RESTOutMessageExchangeImpl(this, mexdao.getInstance().getInstanceId(),
                    mexdao.getMessageExchangeId(), new Resource(resStr[0], "application/xml", resStr[1]));
            outMex.request();

            scheduleRunnable(new UnreliableInvoker(outMex));

            // Scheduling a verification to see if the invoke has really been processed. Otherwise
            // we put it in activity recovery mode (case of a server crash during invocation).
            // TODO the event needs a way to load a mex with getMessageExchange(), not available for now
//            scheduleInvokeCheck(mexdao);
        } finally {
            if (mexdao.getStatus() != MessageExchange.Status.ACK)
                mexdao.setStatus(MessageExchange.Status.ASYNC);
        }
    }


    void onRestMexAck(MessageExchangeDAO mexdao, MessageExchange.Status old, String url) {
        if (mexdao.getPipedMessageExchangeId() != null) /* p2p */{
            p2pCall(mexdao, old);
        } else /* not p2p */{
            RESTInMessageExchangeImpl mymex = (RESTInMessageExchangeImpl) _incomingMexCache.get(mexdao, this);
            mymex.load(mexdao);
            mymex.getResource().setUrl(url);
            if (old == MessageExchange.Status.ASYNC) {
                // Updating url for instantiating mexs so that the created resource url can be returned to the caller
                mymex.onAsyncAck(mexdao);
            }
        }
    }

    // Restful processes don't lazy load their OModel, they need it right away to access the instantiating resource
    protected void latch(int s) { }
    protected void releaseLatch(int s) { }
    protected boolean isLatched(int s) { return false; }
    protected void hydrate() { }
    protected void dehydrate() { }

    public RESTInMessageExchange createRESTMessageExchange(Resource resource, String clientKey) {
        // TODO check the resource matches a provided one
        RESTInMessageExchangeImpl mex = new RESTInMessageExchangeImpl(this, clientKey, resource);
        _incomingMexCache.put(mex);
        return mex;
    }

    MessageExchangeImpl recreateIncomingMex(MessageExchangeDAO mexdao) {
        Resource resource = getResource(mexdao.getResource());
        return new RESTInMessageExchangeImpl(this, mexdao.getMessageExchangeId(), resource);
    }

    public Resource getResource(String serializedForm) {
        int sep = serializedForm.indexOf("~");
        String url = serializedForm.substring(0, sep);
        String method = serializedForm.substring(sep + 1);

        return new Resource(url, "application/xml", method);
    }

    public Resource getInstantiatingUrl(ProcessInstanceDAO instanceDao) {
        return getResource(instanceDao.getInstantiatingUrl());
    }

    protected boolean isInstantiating(String serialized) {
        return isInstantiating(getResource(serialized));
    }
    protected boolean isInstantiating(Resource res) {
        for (Map.Entry<ResourceModel, String> resourceModel : _staticResources.entrySet()) {
            if (resourceModel.getValue().equals(res.getUrl())
                    && resourceModel.getKey().getMethod().equals(res.getMethod())
                    && resourceModel.getKey().isInstantiateResource()) return true;
        }
        return false;
    }

    class UnreliableInvoker implements Runnable {

        RESTOutMessageExchangeImpl _restMex;

        public UnreliableInvoker(RESTOutMessageExchangeImpl mex) {
            _restMex = mex;
        }

        public void run() {
            Throwable err = null;
            try {
                _contexts.mexContext.invokeRestful(_restMex);
                _restMex.setState(RESTOutMessageExchangeImpl.State.HOLD);
            } catch (Throwable t) {
                _restMex.setState(RESTOutMessageExchangeImpl.State.DEAD);
                err = t;
            }

            // Handle system failure in a transaction
            final Throwable ferr = err;
            if (ferr != null) {
                __log.debug("Request failure from instance " + _restMex.getIID() + " for url " + _restMex.getTargetResource().getUrl());
                enqueueInstanceTransaction(_restMex.getIID(),  new Runnable() {
                    public void run() {
                        MessageExchangeDAO mexdao = loadMexDao(_restMex.getMessageExchangeId());
                        MexDaoUtil.setFailed(mexdao, MessageExchange.FailureType.OTHER, ferr.toString());
                        _restMex.setState(RESTOutMessageExchangeImpl.State.DEAD);
                    }
                } );
                return;
            }

            // We proceed handling the response in a transaction. Note that if for some reason the following transaction
            // fails, the unreliable invoke will be in an "unknown" state, and will require manual intervention to either
            // retry or force fail.
            enqueueInstanceTransaction(_restMex.getIID(),  new Runnable() {
                public void run() {
                   MessageExchangeDAO mexdao = loadMexDao(_restMex.getMessageExchangeId());
                   if (_restMex.getStatus() == MessageExchange.Status.ACK) {
                        _restMex.save(mexdao);
                        _restMex.setState(RESTOutMessageExchangeImpl.State.DEAD);
                    } else if (_restMex.getStatus() == MessageExchange.Status.REQ) {
                        MexDaoUtil.setFailed(mexdao, MessageExchange.FailureType.NO_RESPONSE, "No Response");
                        _restMex.setState(RESTOutMessageExchangeImpl.State.DEAD);
                    } else {
                        // Internal error that should not be possible.
                        __log.fatal("InternalError: Unexpected message exchange state!");
                        MexDaoUtil.setFailed(mexdao, MessageExchange.FailureType.OTHER, "Unexpected message exchange state");
                    }

                    executeContinueInstancePartnerRoleResponseReceived(mexdao);
                }
            });
        }
    }
}
