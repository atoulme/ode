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
package org.apache.ode.bpel.rtrep.v2;

import org.apache.ode.bpel.evt.ProcessInstanceStartedEvent;
import org.apache.ode.bpel.rtrep.v2.channels.FaultData;
import org.apache.ode.bpel.rtrep.v2.channels.ParentScopeChannel;
import org.apache.ode.bpel.rtrep.v2.channels.ParentScopeChannelListener;
import org.apache.ode.bpel.rtrep.v2.channels.ReadWriteLockChannel;
import org.apache.ode.bpel.rtrep.v2.channels.TerminationChannel;
import org.apache.ode.jacob.SynchChannel;

import java.util.Set;
import org.w3c.dom.Element;

public class PROCESS extends BpelJacobRunnable {
    private static final long serialVersionUID = 1L;
    private OProcess _oprocess;
    private InstanceGlobals _globals;

    public PROCESS(OProcess process) {
        _oprocess = process;
    }

    public void run() {
        OdeInternalInstance ntive = getBpelRuntime();
        Long scopeInstanceId = ntive.createScopeInstance(null, _oprocess.processScope);

        createGlobals();
        ProcessInstanceStartedEvent evt = new ProcessInstanceStartedEvent();
        evt.setRootScopeId(scopeInstanceId);
        evt.setScopeDeclarationId(_oprocess.processScope.getId());
        ntive.sendEvent(evt);

        ActivityInfo child = new ActivityInfo(genMonotonic(),
            _oprocess.processScope,
            newChannel(TerminationChannel.class), newChannel(ParentScopeChannel.class));
        ScopeFrame processFrame = new ScopeFrame(_oprocess.processScope, scopeInstanceId, null, null,_globals);
        instance(new SCOPE(child, processFrame, new LinkFrame(null)));

        object(new ParentScopeChannelListener(child.parent) {
            private static final long serialVersionUID = -8564969578471906493L;

            public void compensate(OScope scope, SynchChannel ret) {
                assert false;
            }

            public void completed(FaultData fault, Set<CompensationHandler> compensations) {
                OdeInternalInstance nativeAPI = getBpelRuntime();
                if (fault == null) {
                    nativeAPI.completedOk();
                } else {
                    nativeAPI.completedFault(fault);
                }
            }

            public void cancelled() {
                this.completed(null, CompensationHandler.emptySet());
            }

            public void failure(String reason, Element data) {
                FaultData faultData = createFault(OFailureHandling.FAILURE_FAULT_NAME, _oprocess, reason);
                this.completed(faultData, CompensationHandler.emptySet());
            }
        });
    }

    private void createGlobals() {
        _globals = new InstanceGlobals();
        
        // For each variable, we create a lock.
        for (OBase child : _oprocess.getChildren()) 
            if (child instanceof OScope.Variable) {
                OScope.Variable var = (OScope.Variable) child;
                ReadWriteLockChannel vlock = newChannel(ReadWriteLockChannel.class);
                instance(new READWRITELOCK(vlock));
                _globals._varLocks.put(var, vlock);
                
            }
    }
}
