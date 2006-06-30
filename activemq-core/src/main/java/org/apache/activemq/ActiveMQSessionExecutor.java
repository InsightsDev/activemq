/**
 *
 * Copyright 2005-2006 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.activemq;

import java.util.Iterator;
import java.util.List;

import javax.jms.JMSException;

import org.apache.activemq.command.ConsumerId;
import org.apache.activemq.command.MessageDispatch;
import org.apache.activemq.thread.Task;
import org.apache.activemq.thread.TaskRunner;
import org.apache.activemq.util.JMSExceptionSupport;

/**
 * A utility class used by the Session for dispatching messages asynchronously to consumers
 *
 * @version $Revision$
 * @see javax.jms.Session
 */
public class ActiveMQSessionExecutor implements Task {
    
    private ActiveMQSession session;
    private MessageDispatchChannel messageQueue = new MessageDispatchChannel();
    private boolean dispatchedBySessionPool;
    private TaskRunner taskRunner;

    ActiveMQSessionExecutor(ActiveMQSession session) {
        this.session = session;
    }

    void setDispatchedBySessionPool(boolean value) {
        dispatchedBySessionPool = value;
        wakeup();
    }
    

    void execute(MessageDispatch message) throws InterruptedException {
        if (!session.isSessionAsyncDispatch() && !dispatchedBySessionPool){
            dispatch(message);
        }else {
            messageQueue.enqueue(message);
            wakeup();
        }
    }

    private void wakeup() {
        if( !dispatchedBySessionPool && hasUncomsumedMessages() ) {
            if( taskRunner!=null ) {
                try {
                    taskRunner.wakeup();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                while( iterate() )
                    ;
            }
        }
    }

    void executeFirst(MessageDispatch message) {
        messageQueue.enqueueFirst(message);
        wakeup();
    }

    public boolean hasUncomsumedMessages() {
        return !messageQueue.isClosed() && messageQueue.isRunning() && !messageQueue.isEmpty();
    }

    void dispatch(MessageDispatch message){

        // TODO  - we should use a Map for this indexed by consumerId
        
        for (Iterator i = this.session.consumers.iterator(); i.hasNext();) {
            ActiveMQMessageConsumer consumer = (ActiveMQMessageConsumer) i.next();
            ConsumerId consumerId = message.getConsumerId();
            if( consumerId.equals(consumer.getConsumerId()) ) {
                consumer.dispatch(message);
            }
        }
    }
    
    synchronized void start() {
        if( !messageQueue.isRunning() ) {
            messageQueue.start();
            if( session.isSessionAsyncDispatch() || dispatchedBySessionPool ) {
                taskRunner = ActiveMQConnection.SESSION_TASK_RUNNER.createTaskRunner(this, "ActiveMQ Session: "+session.getSessionId());
            }
            wakeup();
        }
    }

    void stop() throws JMSException {
        try {
            if( messageQueue.isRunning() ) {
                messageQueue.stop();
                if( taskRunner!=null ) {
                    taskRunner.shutdown();
                    taskRunner=null;
                }
            }
        } catch (InterruptedException e) {
            throw JMSExceptionSupport.create(e);
        }
    }
    
    boolean isRunning() {
        return messageQueue.isRunning();
    }

    void close() {
        messageQueue.close();
    }

    void clear() {
        messageQueue.clear();
    }

    MessageDispatch dequeueNoWait() {
        return (MessageDispatch) messageQueue.dequeueNoWait();
    }
    
    protected void clearMessagesInProgress(){
        messageQueue.clear();
    }

    public boolean isEmpty() {
        return messageQueue.isEmpty();
    }

    public boolean iterate() {
        MessageDispatch message = messageQueue.dequeueNoWait();
        if( message==null ) {
            return false;
        } else {
            dispatch(message);
            return !messageQueue.isEmpty();
        }
    }

	List getUnconsumedMessages() {
		return messageQueue.removeAll();
	}
    
}