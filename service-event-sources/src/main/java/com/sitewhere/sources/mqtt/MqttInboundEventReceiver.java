/*
 * Copyright (c) SiteWhere, LLC. All rights reserved. http://www.sitewhere.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package com.sitewhere.sources.mqtt;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.fusesource.hawtdispatch.ShutdownException;
import org.fusesource.mqtt.client.Future;
import org.fusesource.mqtt.client.FutureConnection;
import org.fusesource.mqtt.client.Message;
import org.fusesource.mqtt.client.QoS;
import org.fusesource.mqtt.client.Topic;

import com.sitewhere.communication.mqtt.MqttLifecycleComponent;
import com.sitewhere.sources.spi.IInboundEventReceiver;
import com.sitewhere.sources.spi.IInboundEventSource;
import com.sitewhere.spi.SiteWhereException;
import com.sitewhere.spi.server.lifecycle.ILifecycleProgressMonitor;
import com.sitewhere.spi.server.lifecycle.LifecycleComponentType;

/**
 * Implementation of {@link IInboundEventReceiver} that subscribes to an MQTT
 * topic and pulls the message contents into SiteWhere for processing.
 * 
 * @author Derek
 */
public class MqttInboundEventReceiver extends MqttLifecycleComponent implements IInboundEventReceiver<byte[]> {

    /** Default subscribed topic name */
    public static final String DEFAULT_TOPIC = "SiteWhere/input/protobuf";

    /** Parent event source */
    private IInboundEventSource<byte[]> eventSource;

    /** Topic name */
    private String topic = DEFAULT_TOPIC;

    /** Shared MQTT connection */
    private FutureConnection connection;

    /** Used to execute MQTT subscribe in separate thread */
    private ExecutorService executor;

    /** Count of received events */
    private AtomicInteger eventCount = new AtomicInteger();

    public MqttInboundEventReceiver() {
	super(LifecycleComponentType.InboundEventReceiver);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.sitewhere.device.communication.mqtt.MqttLifecycleComponent#start(com.
     * sitewhere.spi.server.lifecycle.ILifecycleProgressMonitor)
     */
    @Override
    public void start(ILifecycleProgressMonitor monitor) throws SiteWhereException {
	super.start(monitor);

	this.executor = Executors.newSingleThreadExecutor(new SubscribersThreadFactory());
	getLogger().info("Receiver connecting to MQTT broker at '" + getBrokerInfo() + "'...");
	connection = getConnection();
	getLogger().info("Receiver connected to MQTT broker.");

	// Subscribe to chosen topic.
	Topic[] topics = { new Topic(getTopic(), QoS.AT_LEAST_ONCE) };
	try {
	    Future<byte[]> future = connection.subscribe(topics);
	    future.await();

	    getLogger().info("Subscribed to events on MQTT topic: " + getTopic());
	} catch (Exception e) {
	    throw new SiteWhereException("Exception while attempting to subscribe to MQTT topic: " + getTopic(), e);
	}

	// Handle message processing in separate thread.
	executor.execute(new MqttSubscriptionProcessor());
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.spi.device.communication.IInboundEventReceiver#
     * getDisplayName()
     */
    @Override
    public String getDisplayName() {
	return getProtocol() + "://" + getHostname() + ":" + getPort() + "/" + getTopic();
    }

    /** Used for naming consumer threads */
    private class SubscribersThreadFactory implements ThreadFactory {

	/** Counts threads */
	private AtomicInteger counter = new AtomicInteger();

	public Thread newThread(Runnable r) {
	    return new Thread(r, "SiteWhere MQTT(" + getEventSource().getSourceId() + " - " + getTopic() + ") Receiver "
		    + counter.incrementAndGet());
	}
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.spi.device.communication.IInboundEventReceiver#
     * onEventPayloadReceived (java.lang.Object, java.util.Map)
     */
    @Override
    public void onEventPayloadReceived(byte[] payload, Map<String, Object> metadata) {
	eventCount.incrementAndGet();
	getEventSource().onEncodedEventReceived(MqttInboundEventReceiver.this, payload, metadata);
    }

    /**
     * Pulls messages from the MQTT topic and puts them on the queue for this
     * receiver.
     * 
     * @author Derek
     */
    private class MqttSubscriptionProcessor implements Runnable {

	@Override
	public void run() {
	    getLogger().info("Started MQTT subscription processing thread.");
	    while (true) {
		try {
		    Future<Message> future = connection.receive();
		    Message message = future.await();
		    message.ack();
		    onEventPayloadReceived(message.getPayload(), null);
		} catch (InterruptedException e) {
		    break;
		} catch (Throwable e) {
		    getLogger().error(e);
		}
	    }
	}
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.device.communication.mqtt.MqttLifecycleComponent#stop(com.
     * sitewhere.spi.server.lifecycle.ILifecycleProgressMonitor)
     */
    @Override
    public void stop(ILifecycleProgressMonitor monitor) throws SiteWhereException {
	if (executor != null) {
	    executor.shutdownNow();
	}
	if (connection != null) {
	    try {
		connection.disconnect().await();
		connection.kill().await();
	    } catch (ShutdownException e) {
		getLogger().info("Dispatcher has already been shut down.");
	    } catch (Exception e) {
		getLogger().error("Error shutting down MQTT device event receiver.", e);
	    }
	}
	super.stop(monitor);
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.spi.device.communication.IInboundEventReceiver#
     * getEventSource()
     */
    public IInboundEventSource<byte[]> getEventSource() {
	return eventSource;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.sitewhere.spi.device.communication.IInboundEventReceiver#
     * setEventSource(com .sitewhere.spi.device.communication.IInboundEventSource)
     */
    public void setEventSource(IInboundEventSource<byte[]> eventSource) {
	this.eventSource = eventSource;
    }

    public String getTopic() {
	return topic;
    }

    public void setTopic(String topic) {
	this.topic = topic;
    }
}