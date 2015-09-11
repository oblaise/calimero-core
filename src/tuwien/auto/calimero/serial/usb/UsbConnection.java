/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2015 B. Malinowsky

    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program; if not, write to the Free Software
    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

    Linking this library statically or dynamically with other modules is
    making a combined work based on this library. Thus, the terms and
    conditions of the GNU General Public License cover the whole
    combination.

    As a special exception, the copyright holders of this library give you
    permission to link this library with independent modules to produce an
    executable, regardless of the license terms of these independent
    modules, and to copy and distribute the resulting executable under terms
    of your choice, provided that you also meet, for each linked independent
    module, the terms and conditions of the license of that module. An
    independent module is a module which is not derived from or based on
    this library. If you modify this library, you may extend this exception
    to your version of the library, but you are not obligated to do so. If
    you do not wish to do so, delete this exception statement from your
    version.
*/

package tuwien.auto.calimero.serial.usb;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.usb.UsbClaimException;
import javax.usb.UsbConfiguration;
import javax.usb.UsbConst;
import javax.usb.UsbDevice;
import javax.usb.UsbDeviceDescriptor;
import javax.usb.UsbDisconnectedException;
import javax.usb.UsbEndpoint;
import javax.usb.UsbException;
import javax.usb.UsbHostManager;
import javax.usb.UsbHub;
import javax.usb.UsbInterface;
import javax.usb.UsbInterfacePolicy;
import javax.usb.UsbIrp;
import javax.usb.UsbNotActiveException;
import javax.usb.UsbNotClaimedException;
import javax.usb.UsbNotOpenException;
import javax.usb.UsbPipe;
import javax.usb.UsbPlatformException;
import javax.usb.event.UsbPipeDataEvent;
import javax.usb.event.UsbPipeErrorEvent;
import javax.usb.event.UsbPipeEvent;
import javax.usb.event.UsbPipeListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.usb4java.Context;
import org.usb4java.DescriptorUtils;
import org.usb4java.Device;
import org.usb4java.DeviceDescriptor;
import org.usb4java.DeviceHandle;
import org.usb4java.DeviceList;
import org.usb4java.LibUsb;

import tuwien.auto.calimero.CloseEvent;
import tuwien.auto.calimero.DataUnitBuilder;
import tuwien.auto.calimero.FrameEvent;
import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.KNXFormatException;
import tuwien.auto.calimero.KNXIllegalArgumentException;
import tuwien.auto.calimero.KNXListener;
import tuwien.auto.calimero.KNXTimeoutException;
import tuwien.auto.calimero.cemi.CEMIFactory;
import tuwien.auto.calimero.internal.EventListeners;
import tuwien.auto.calimero.serial.KNXPortClosedException;
import tuwien.auto.calimero.serial.usb.HidReport.BusAccessServerFeature;
import tuwien.auto.calimero.serial.usb.HidReportHeader.PacketType;
import tuwien.auto.calimero.serial.usb.TransferProtocolHeader.BusAccessServerService;
import tuwien.auto.calimero.serial.usb.TransferProtocolHeader.KnxTunnelEmi;
import tuwien.auto.calimero.serial.usb.TransferProtocolHeader.Protocol;

/**
 * KNX USB connection providing EMI data exchange and Bus Access Server Feature service. The
 * implementation for USB is based on javax.usb and usb4java.
 *
 * @author B. Malinowsky
 */
public class UsbConnection implements AutoCloseable
{
	/**
	 * Available EMI types and their respective bit value representation.
	 */
	public enum EmiType {
		// ??? The encoding of this enumeration is for the supported EMI types response only.
		// The feature protocol and the active EMI type uses the KnxTunnelEmi encoding.
		Emi1(1 << 0, KnxTunnelEmi.Emi1),
		Emi2(1 << 1, KnxTunnelEmi.Emi2),
		CEmi(1 << 2, KnxTunnelEmi.CEmi);

		static EnumSet<EmiType> fromBits(final int bitfield)
		{
			final EnumSet<EmiType> types = EnumSet.noneOf(EmiType.class);
			for (final EmiType t : EmiType.values())
				if ((bitfield & t.bit) == t.bit)
					types.add(t);
			return types;
		}

		final int bit;
		// the KNX tunneling EMI enumeration values are different!
		public final KnxTunnelEmi emi;

		private EmiType(final int bit, final KnxTunnelEmi emi)
		{
			this.bit = bit;
			this.emi = emi;
		}
	};

	private static final int[] vendorIds = {
		0x135e, // Insta: also used in Hager, Jung, Merten, Berker, Gira
		0x0e77, // Siemens: also used in Weinzierl, Merlin, Hensel
		0x145c, // Busch-Jaeger
		0x147b, // ABB stotz-kontakt
	};

	private static final int[] productIds = {
		// uses Insta
		0x0020, 0x0021, 0x0022, 0x0023, 0x0024, 0x0025, 0x0026,
		// uses Siemens
		0x0102, 0x0104, 0x0111, 0x0112, 0x0121, 0x0141, 0x2001,
		// uses BJ
		0x1330, // BJ flush-mounted
		// uses ABB
		0x5120 };

	// EP in/out: USB endpoint for asynchronous KNX data transfer over interrupt pipe
	private static final byte knxEndpointOut = (byte) 0x02;
	private static final byte knxEndpointIn = (byte) 0x81;

	// maximum reply time for a response service is 1000 ms
	// the additional milliseconds allow for delay of slow interfaces and OS crap
	private static final int tunnelingTimeout = 1000 + 500; // ms

	private static final String logPrefix = "calimero.usb";
	private static final Logger slogger = LoggerFactory.getLogger(logPrefix);
	private final Logger logger;
	private final String name;

	private final EventListeners<KNXListener> listeners = new EventListeners<>();

	private final UsbInterface knxUsbIf;
	private final UsbPipe out;
	private final UsbPipe in;

//	private final boolean useFallback = false;
//	private VirtualComPort comPort;

	/*
	 Device Feature Service protocol:
	 Device Feature Get is always answered by Device Feature Response
	 Device Feature Get is only sent by the USB bus access client-side
	 Device Feature Set and Info are not answered
	 Device Feature Info is only sent by the USB bus access server-side
	 */

	private final Object responseLock = new Object();
	private HidReport response;

	// TODO Make sure list is not filled with junk data over time, e.g., add timestamp and sweep
	// after > 5 * tunnelingTimeout. Also identify and log unknown entries.
	// Not tested, because partial reports are not used currently
	private final List<HidReport> partialReportList = Collections
			.synchronizedList(new ArrayList<>());

	private final UsbCallback callback = new UsbCallback();

	private final class UsbCallback extends Thread implements UsbPipeListener, UsbInterfacePolicy
	{
		private volatile boolean close;

		{
			setDaemon(true);
			setName("Calimero USB callback");
		}

		@Override
		public void run()
		{
			try {
				while (!close)
					in.syncSubmit(new byte[64]);

			}
			catch (final UsbNotActiveException | UsbNotOpenException | IllegalArgumentException
					| UsbDisconnectedException | UsbException e) {
				if (!close)
					close(CloseEvent.INTERNAL, e.getMessage());
			}
		}

		@Override
		public void errorEventOccurred(final UsbPipeErrorEvent event)
		{
			final byte epaddr = endpointAddress(event);
			final int idx = epaddr & UsbConst.ENDPOINT_NUMBER_MASK;
			final String dir = DescriptorUtils.getDirectionName(epaddr);

			final UsbException e = event.getUsbException();
			logger.error("EP {} {} error event for I/O request, {}", idx, dir, e.toString());
		}

		@Override
		public void dataEventOccurred(final UsbPipeDataEvent event)
		{
			final byte epaddr = endpointAddress(event);
			final int idx = epaddr & UsbConst.ENDPOINT_NUMBER_MASK;
			final String dir = DescriptorUtils.getDirectionName(epaddr);

			final byte[] data = event.getData();
			logger.trace("EP {} {} I/O request {}", idx, dir, DataUnitBuilder.toHex(data, ""));
			try {
				final HidReport r = new HidReport(data);
				final TransferProtocolHeader tph = r.getTransferProtocolHeader();
				if (tph == null)
					assemblePartialPackets(r);
				else if (tph.getProtocol() == Protocol.KnxTunnel)
					fireFrameReceived((KnxTunnelEmi) tph.getService(), r.getData());
				else if (tph.getProtocol() == Protocol.BusAccessServerFeature) {
					// check whether we are waiting for a device feature response service
					if (tph.getService() == BusAccessServerService.Response)
						setResponse(r);
					else if (tph.getService() == BusAccessServerService.Info) {
						final BusAccessServerFeature feature = r.getFeatureId();
						logger.info("{} {}", feature, DataUnitBuilder.toHex(r.getData(), ""));
					}
				}
				else
					logger.warn("unexpected service {}: {}", tph.getService(),
							DataUnitBuilder.toHex(data, ""));
			}
			catch (final KNXFormatException | RuntimeException e) {
				logger.error("creating HID class report", e);
			}
		}

		@Override
		public boolean forceClaim(final UsbInterface usbInterface)
		{
			return true;
		}

		private byte endpointAddress(final UsbPipeEvent event)
		{
			final UsbEndpoint ep = event.getUsbPipe().getUsbEndpoint();
			return ep.getUsbEndpointDescriptor().bEndpointAddress();
		}

		void quit()
		{
			close = true;
		}
	}

	static {
		try {
			printDevices();
		}
		catch (final SecurityException | UsbException e) {
			slogger.error("Enumerate USB devices, " + e);
		}
		try {
			final StringBuilder sb = new StringBuilder();
			for (final UsbDevice d : getKnxDevices()) {
				try {
					sb.append("\n").append(printInfo(d, slogger, " |   "));
				}
				catch (final UsbException ignore) {}
			}
			slogger.info("Found KNX devices:{}", sb);
		}
		catch (final SecurityException | UsbException e) {}
	}

	public static void updateDeviceList() throws SecurityException, UsbException
	{
		((org.usb4java.javax.Services) UsbHostManager.getUsbServices()).scan();
	}

	public static List<UsbDevice> getDevices() throws SecurityException, UsbException
	{
		return collect(getRootHub());
	}

	/**
	 * Returns the list of KNX devices currently attached to the host, based on known KNX vendor
	 * IDs.
	 *
	 * @return the list of found KNX devices
	 * @throws SecurityException on error accessing javax.usb
	 * @throws UsbException on error accessing javax.usb
	 */
	public static List<UsbDevice> getKnxDevices() throws SecurityException, UsbException
	{
		final List<UsbDevice> knx = new ArrayList<>();
		for (final UsbDevice d : getDevices()) {
			final int vendor = d.getUsbDeviceDescriptor().idVendor() & 0xffff;
			for (final int v : vendorIds)
				if (v == vendor)
					knx.add(d);
		}
		return knx;
	}

	public static void printDevices() throws SecurityException, UsbException
	{
		final StringBuilder sb = new StringBuilder();
		traverse(getRootHub(), sb, "");
		slogger.info("Enumerate USB devices\n{}", sb);

		// Use the low-level API, because on Windows the string descriptors cause problems
		if (slogger.isDebugEnabled())
			slogger.debug("Enumerate USB devices using the low-level API\n{}",
					getDeviceDescriptionsLowLevel().stream().collect(Collectors.joining("\n")));
	}

	public UsbConnection(final String device) throws KNXException
	{
		this(findDevice(device), device);
	}

	// TODO we use the first matching USB device we find, current param list is not sufficient!
	public UsbConnection(final int vendorId, final int productId) throws KNXException
	{
		this(findDevice(vendorId, productId), toDeviceId(vendorId, productId));
	}

	private UsbConnection(final UsbDevice device, final String name) throws KNXException
	{
		try {
			this.name = name;
			logger = LoggerFactory.getLogger(logPrefix + "." + getName());
			knxUsbIf = open(device);
			out = open(knxUsbIf, knxEndpointOut);
			in = open(knxUsbIf, knxEndpointIn);
			in.addUsbPipeListener(callback);
			// if necessary, unclog the incoming pipe
			UsbIrp irp;
			do {
				irp = in.asyncSubmit(new byte[64]);
				irp.waitUntilComplete(10);
			}
			while (irp.isComplete());

			callback.start();
		}
		catch (final UsbNotActiveException | UsbDisconnectedException | UsbNotClaimedException
				| UsbException e) {
			throw new KNXException("open USB connection", e);
		}
	}

	/**
	 * Adds the specified event listener <code>l</code> to receive events from this connection. If
	 * <code>l</code> was already added as listener, no action is performed.
	 *
	 * @param l the listener to add
	 */
	public void addConnectionListener(final KNXListener l)
	{
		listeners.add(l);
	}

	/**
	 * Removes the specified event listener <code>l</code>, so it does no longer receive events from
	 * this connection. If <code>l</code> was not added in the first place, no action is performed.
	 *
	 * @param l the listener to remove
	 */
	public void removeConnectionListener(final KNXListener l)
	{
		listeners.remove(l);
	}

	public void send(final HidReport report, final boolean blocking) throws KNXPortClosedException,
		KNXTimeoutException
	{
		try {
			final byte[] data = report.toByteArray();
			logger.trace("sending I/O request {}", DataUnitBuilder.toHex(data, ""));
			out.syncSubmit(data);
		}
		catch (final UsbException | UsbNotActiveException | UsbNotClaimedException
				| UsbDisconnectedException e) {
			close();
			throw new KNXPortClosedException("error sending report over USB", name, e);
		}
	}

	/**
	 * Returns the KNX device descriptor type 0 (mask version), use {@link DeviceDescriptor} for
	 * decoding. The returned descriptor information format for device descriptor type 0 is as
	 * follows (MSB to LSB):<br>
	 * <code>| Mask Type (8 bit) | Firmware Version (8 bit) |</code><br>
	 * with the mask type split up into<br>
	 * <code>| Medium Type (4 bit) | Firmware Type (4 bit)|</code><br>
	 * and the firmware version split up into<br>
	 * <code>| Version (4 bit) | Subcode (4 bit) |</code><br>
	 * <br>
	 *
	 * @return the descriptor type 0
	 * @throws KNXPortClosedException on closed port
	 * @throws KNXTimeoutException on response timeout
	 * @throws InterruptedException on interrupt
	 */
	public final int getDeviceDescriptorType0() throws KNXPortClosedException, KNXTimeoutException,
		InterruptedException
	{
		// device descriptor type 0 has a 2 byte structure
		return (int) toUnsigned(getFeature(BusAccessServerFeature.DeviceDescriptorType0));
	}

	/**
	 * @return the EMI types supported by the KNX USB device
	 * @throws KNXPortClosedException on closed port
	 * @throws KNXTimeoutException on response timeout
	 * @throws InterruptedException on interrupt
	 */
	public final EnumSet<EmiType> getSupportedEmiTypes() throws KNXPortClosedException,
		KNXTimeoutException, InterruptedException
	{
		return EmiType.fromBits(getFeature(BusAccessServerFeature.SupportedEmiTypes)[1]);
	}

	/**
	 * @return the currently active EMI type in the KNX USB device
	 * @throws KNXPortClosedException on closed port
	 * @throws KNXTimeoutException on response timeout
	 * @throws InterruptedException on interrupt
	 */
	public final EmiType getActiveEmiType() throws KNXPortClosedException, KNXTimeoutException,
		InterruptedException
	{
		final int bits = (int) toUnsigned(getFeature(BusAccessServerFeature.ActiveEmiType));
		final EnumSet<EmiType> all = EnumSet.allOf(EmiType.class);
		for (final EmiType t : all) {
			if (t.emi.id() == bits)
				return t;
		}
		// TODO would an EmiType element "NotSet" make sense? at least one device I know returns
		// 0 in uninitialized state
		throw new KNXIllegalArgumentException("unspecified EMI type " + bits);
	}

	/**
	 * Sets the active EMI type for communication. Before setting an active EMI type, the supported
	 * EMI types should be checked using {@link #getSupportedEmiTypes()}. If only one EMI type is
	 * supported, KNX USB device support for this method is optional.
	 *
	 * @param active the EMI type to activate for communication
	 * @throws KNXPortClosedException on closed port
	 * @throws KNXTimeoutException on response timeout
	 */
	public final void setActiveEmiType(final EmiType active) throws KNXPortClosedException,
		KNXTimeoutException
	{
		final HidReport r = HidReport.createFeatureService(BusAccessServerService.Set,
				BusAccessServerFeature.ActiveEmiType, new byte[] { (byte) active.emi.id() });
		send(r, true);
	}

	/**
	 * @return current state of the KNX connection, active/not active
	 * @throws KNXPortClosedException on closed port
	 * @throws KNXTimeoutException on response timeout
	 * @throws InterruptedException on interrupt
	 */
	public final boolean isKnxConnectionActive() throws KNXPortClosedException,
		KNXTimeoutException, InterruptedException
	{
		final int data = getFeature(BusAccessServerFeature.ConnectionStatus)[0];
		return (data & 0x01) == 0x01;
	}

	/**
	 * @return the KNX USB manufacturer code as 16 bit unsigned value
	 * @throws KNXPortClosedException on closed port
	 * @throws KNXTimeoutException on response timeout
	 * @throws InterruptedException on interrupt
	 */
	public final int getManufacturerCode() throws KNXPortClosedException, KNXTimeoutException,
		InterruptedException
	{
		return (int) toUnsigned(getFeature(BusAccessServerFeature.Manufacturer));
	}

	/**
	 * @return the name of this USB connection, usually in the format {@code <vendorID>:<productID>}
	 */
	public final String getName()
	{
		return name;
	}

	@Override
	public void close()
	{
		close(CloseEvent.CLIENT_REQUEST, "user request");
	}

	private UsbInterface open(final UsbDevice device) throws UsbClaimException,
		UsbNotActiveException, UsbDisconnectedException, UsbException
	{
		logger.info(printInfo(device, logger, ""));

		final UsbConfiguration configuration = device.getActiveUsbConfiguration();
		@SuppressWarnings("unchecked")
		final List<UsbInterface> interfaces = configuration.getUsbInterfaces();
		byte epAddressOut = 0;
		byte epAddressIn = 0;
		for (final UsbInterface uif : interfaces) {
			@SuppressWarnings("unchecked")
			final List<UsbInterface> settings = uif.getSettings();
			// iterate over all alternate settings this interface provides
			for (final UsbInterface alt : settings) {
				logger.trace("Interface {}", alt);
				// KNX USB has a HID class interface
				final int INTERFACE_CLASS_HID = 0x03;
				final byte ifClass = alt.getUsbInterfaceDescriptor().bInterfaceClass();
				if (ifClass != INTERFACE_CLASS_HID)
					logger.warn("Interface doesn't look right, no HID class");
				else {
					@SuppressWarnings("unchecked")
					final List<UsbEndpoint> endpoints = alt.getUsbEndpoints();
					for (final UsbEndpoint endpoint : endpoints) {
						final byte addr = endpoint.getUsbEndpointDescriptor().bEndpointAddress();
						final int index = addr & UsbConst.ENDPOINT_NUMBER_MASK;

						final String inout = DescriptorUtils.getDirectionName(addr);
						logger.trace("EP {} {}", index, inout);

						final boolean epIn = (addr & LibUsb.ENDPOINT_IN) == 0 ? false : true;
						if (epIn && epAddressIn == 0)
							epAddressIn = addr;
						if (!epIn && epAddressOut == 0)
							epAddressOut = addr;
					}
				}
			}
		}
		logger.debug("Found USB device endpoint addresses OUT 0x{}, IN 0x{}",
				Integer.toHexString(epAddressOut & 0xff), Integer.toHexString(epAddressIn & 0xff));
		// ??? all devices I know use 0, so just stick to it for now
		final UsbInterface usbIf = configuration.getUsbInterface((byte) 0);
		try {
			usbIf.claim();
		}
		catch (final UsbClaimException | UsbPlatformException e) {
			// At least on Linux, we might have to detach the kernel driver. Strangely,
			// a failed claim presents itself as UsbPlatformException, indicating a busy device.
			// Force unload any kernel USB drivers, might work on Linux/OSX, not on Windows.
			usbIf.claim(callback);
		}
		return usbIf;
	}

	private UsbPipe open(final UsbInterface usbIf, final byte endpointAddress) throws KNXException,
		UsbNotActiveException, UsbNotClaimedException, UsbDisconnectedException, UsbException
	{
		final UsbEndpoint epout = usbIf.getUsbEndpoint(endpointAddress);
		if (epout == null)
			throw new KNXException(usbIf.getUsbConfiguration().getUsbDevice()
					+ " contains no KNX USB data endpoint 0x"
					+ Integer.toUnsignedString(endpointAddress, 16));
		final UsbPipe pipe = epout.getUsbPipe();
		pipe.open();
		return pipe;
	}

	private void close(final int initiator, final String reason)
	{
		try {
			in.removeUsbPipeListener(callback);
			callback.quit();

			out.abortAllSubmissions();
			out.close();
			// TODO this causes our callback thread to quit with exception
			in.abortAllSubmissions();
			in.close();

			String ifname = "" + knxUsbIf.getUsbInterfaceDescriptor().bInterfaceNumber();
			try {
				final String s = knxUsbIf.getInterfaceString();
				if (s != null)
					ifname = s;
			}
			catch (final UnsupportedEncodingException e) {}
			logger.trace("release USB interface {}, active={}, claimed={}", ifname,
					knxUsbIf.isActive(), knxUsbIf.isClaimed());
			knxUsbIf.release();
		}
		catch (final UsbNotActiveException | UsbNotOpenException | UsbDisconnectedException
				| UsbException e) {
			logger.warn("close connection", e);
		}
		listeners.fire(l -> l.connectionClosed(new CloseEvent(this, initiator, reason)));
	}

	private byte[] getFeature(final BusAccessServerFeature feature) throws InterruptedException,
		KNXPortClosedException, KNXTimeoutException
	{
		final HidReport r = HidReport.createFeatureService(BusAccessServerService.Get, feature,
				new byte[0]);
		send(r, true);
		final HidReport res = waitForResponse();
		return res.getData();
	}

	private HidReport waitForResponse() throws InterruptedException, KNXTimeoutException
	{
		long remaining = tunnelingTimeout;
		final long end = System.currentTimeMillis() + remaining;
		while (remaining > 0) {
			synchronized (responseLock) {
				if (response != null) {
					final HidReport r = response;
					response = null;
					return r;
				}
				responseLock.wait(remaining);
			}
			remaining = end - System.currentTimeMillis();
		}
		throw new KNXTimeoutException("waiting for response");
	}

	private void setResponse(final HidReport response)
	{
		synchronized (responseLock) {
			this.response = response;
			responseLock.notify();
		}
	}

	// NYI partial packets are not tested
	private void assemblePartialPackets(final HidReport part) throws KNXFormatException
	{
		partialReportList.add(part);
		if (!part.getReportHeader().getPacketType().contains(PacketType.End))
			return;

		final ByteArrayOutputStream data = new ByteArrayOutputStream();
		KnxTunnelEmi emiType = null;
		for (int i = 0; i < partialReportList.size(); i++) {
			final HidReport r = partialReportList.get(i);
			if (r.getReportHeader().getSeqNumber() != i + 1) {
				// unexpected order, ignore complete KNX frame and delete received reports
				partialReportList.clear();
				logger.warn("received out of order HID report - ignore complete KNX frame");
				return;
			}
			if (r.getReportHeader().getPacketType().contains(PacketType.Start))
				emiType = (KnxTunnelEmi) r.getTransferProtocolHeader().getService();
			final byte[] body = r.getData();
			data.write(body, 0, body.length);
		}
		final byte[] assembled = data.toByteArray();
		logger.debug("assembling completed using {} partial packets, KNX data frame: {}",
				partialReportList.size(), DataUnitBuilder.toHex(assembled, " "));
		partialReportList.clear();
		fireFrameReceived(emiType, assembled);
	}

	/**
	 * Fires a frame received event ({@link KNXListener#frameReceived(FrameEvent)}) for the supplied
	 * EMI <code>frame</code>.
	 *
	 * @param frame the EMI1/EMI2/cEMI L-data frame to generate the event for
	 * @throws KNXFormatException on error creating cEMI message
	 */
	private void fireFrameReceived(final KnxTunnelEmi emiType, final byte[] frame)
		throws KNXFormatException
	{
		logger.debug("received {} frame {}", emiType, DataUnitBuilder.toHex(frame, ""));
		final FrameEvent fe = emiType == KnxTunnelEmi.CEmi ? new FrameEvent(this,
				CEMIFactory.create(frame, 0, frame.length)) : new FrameEvent(this, frame);
		listeners.fire(l -> l.frameReceived(fe));
	}

	private static UsbHub getRootHub() throws SecurityException, UsbException
	{
		return UsbHostManager.getUsbServices().getRootUsbHub();
	}

	@SuppressWarnings("unchecked")
	private static List<UsbDevice> getAttachedDevices(final UsbHub hub)
	{
		return hub.getAttachedUsbDevices();
	}

	private static UsbDevice findDevice(final int vendorId, final int productId)
		throws KNXException
	{
		try {
			return findDevice(getRootHub(), vendorId, productId);
		}
		catch (final SecurityException | UsbException e) {
			throw new KNXException("Accessing USB root hub", e);
		}
	}

	private static UsbDevice findDevice(final UsbHub hub, final int vendorId, final int productId)
		throws KNXException
	{
		for (final UsbDevice d : getAttachedDevices(hub)) {
			final UsbDeviceDescriptor dd = d.getUsbDeviceDescriptor();
			if ((dd.idVendor() & 0xffff) == vendorId && (dd.idProduct() & 0xffff) == productId)
				return d;
			if (d.isUsbHub()) {
				try {
					return findDevice((UsbHub) d, vendorId, productId);
				}
				catch (final KNXException e) {}
			}
		}
		throw new KNXException(toDeviceId(vendorId, productId) + " not found");
	}

	private static UsbDevice findDevice(final String device) throws KNXException
	{
		try {
			// check vendorId:productId format
			final String[] split = device.split(":");
			if (split.length == 2) {
				try {
					final int vendorId = Integer.parseInt(split[0], 16);
					final int productId = Integer.parseInt(split[1], 16);
					return findDevice(getRootHub(), vendorId, productId);
				}
				catch (final NumberFormatException expected) {}
			}
			// check if device name is a substring in one of the USB device strings
			return findDeviceByNameLowLevel(device);
//			return findDeviceByName(getRootHub(), device.toLowerCase());
		}
		catch (final SecurityException | UsbException | UsbDisconnectedException e) {
			throw new KNXException("find USB device by name " + device, e);
		}
	}

	// find device by name using high-level API
	// won't work on Windows, because of libusb overflow error on getting the language descriptor
//	private static UsbDevice findDeviceByName(final UsbHub hub, final String device)
//		throws UsbDisconnectedException, UsbException, KNXException
//	{
//		for (final UsbDevice d : getAttachedDevices(hub)) {
//			try {
//				final String mf = d.getManufacturerString();
//				if (mf != null && mf.toLowerCase().contains(device))
//					return d;
//				final String prod = d.getProductString();
//				if (prod != null && prod.toLowerCase().contains(device))
//					return d;
//			}
//			catch (final UnsupportedEncodingException | UsbException e) {}
//			try {
//				if (d.isUsbHub())
//					return findDeviceByName((UsbHub) d, device);
//			}
//			catch (final KNXException e) {}
//		}
//		throw new KNXException("no USB device found by name " + device);
//	}

	private static List<UsbDevice> collect(final UsbDevice device)
	{
		final List<UsbDevice> l = new ArrayList<>();
		if (device.isUsbHub())
			getAttachedDevices((UsbHub) device).forEach(d -> l.addAll(collect(d)));
		else
			l.add(device);
		return l;
	}

	private static void traverse(final UsbDevice device, final StringBuilder sb,
		final String indent)
	{
		try {
			sb.append(printInfo(device, slogger, indent));
		}
		catch (final UsbException e) {
			slogger.warn("Accessing USB device, " + e);
		}
		if (device.isUsbHub())
			for (final Iterator<UsbDevice> i = getAttachedDevices((UsbHub) device).iterator(); i
					.hasNext();)
				traverse(i.next(), sb.append("\n"), indent + (i.hasNext() ? " |   " : "     "));
	}

	private static String printInfo(final UsbDevice device, final Logger l, final String indent)
		throws UsbException
	{
		final StringBuilder sb = new StringBuilder();
		final UsbDeviceDescriptor dd = device.getUsbDeviceDescriptor();
		final String s = indent.isEmpty() ? "" : indent.substring(0, indent.length() - 5) + " |--";
		// vendor ID is mandatory for KNX USB data interface
		sb.append(s).append(device.toString());

		// virtual devices don't contain string descriptors
		final boolean virtual = device instanceof UsbHub && ((UsbHub) device).isRootUsbHub();
		if (virtual)
			return sb.toString();

		// manufacturer is mandatory for KNX USB data interface
		final byte manufacturer = dd.iManufacturer();
		// product support is optional for KNX USB data interface
		final byte product = dd.iProduct();
		final byte sno = dd.iSerialNumber();
		try {
			String desc = indent;
			if (product != 0)
				desc += "" + device.getString(product);
			if (manufacturer != 0)
				desc += " (" + device.getString(manufacturer) + ")";
			if (desc != indent)
				sb.append("\n").append(desc);
			if (sno != 0)
				sb.append("\n").append(indent).append("S/N: ").append(device.getString(sno));
		}
		catch (final UnsupportedEncodingException e) {
			l.error("Java platform lacks support for the required standard charset UTF-16LE");
			e.printStackTrace();
		}
		catch (final UsbPlatformException e) {
			// Thrown on Win 7/8 (USB error 8) on calling device.getString on most USB interfaces.
			// Therefore, catch it here, but don't issue any error/warning
			l.debug("extracting USB device strings, {}", e.toString());
		}
		return sb.toString();
	}

	// Cross-platform way to do name lookup for USB devices, using the low-level API.
	// Parse the USB device string descriptions for name, extract the vendor:product ID
	// string. Pass that ID to findDevice. which will do the lookup by ID.
	private static UsbDevice findDeviceByNameLowLevel(final String name) throws KNXException
	{
		final List<String> list = getDeviceDescriptionsLowLevel();
		list.removeIf(i -> i.toLowerCase().indexOf(name.toLowerCase()) == -1);
		if (list.isEmpty())
			throw new KNXException("no USB device found by name " + name);

		final String desc = list.get(0);
		final String id = desc.substring(desc.indexOf("ID") + 3, desc.indexOf("\n"));
		return findDevice(id);
	}

	// On Win 7/8/8.1, libusb has a problem with overflows on getting the language descriptor,
	// so we can't read out the device string descriptors.
	// This method avoids any further issues down the road by using the ASCII descriptors.
	private static List<String> getDeviceDescriptionsLowLevel()
	{
		final Context ctx = new Context();
		final int err = LibUsb.init(ctx);
		if (err != 0) {
			slogger.error("LibUsb initialization error {}: {}", -err, LibUsb.strError(err));
			return Collections.emptyList();
		}
		try {
			final DeviceList list = new DeviceList();
			final int res = LibUsb.getDeviceList(ctx, list);
			if (res < 0) {
				slogger.error("LibUsb device list error {}: {}", -res, LibUsb.strError(res));
				return Collections.emptyList();
			}
			try {
				return StreamSupport.stream(list.spliterator(), false)
						.map(UsbConnection::printInfo).collect(Collectors.toList());
			}
			finally {
				LibUsb.freeDeviceList(list, true);
			}
		}
		finally {
			LibUsb.exit(ctx);
		}
	}

	// libusb low level
	private static String printInfo(final Device device)
	{
		final int bus = LibUsb.getBusNumber(device);
		final int address = LibUsb.getDeviceAddress(device);
		int vendor = 0;
		int product = 0;

		final DeviceDescriptor d = new DeviceDescriptor();
		int err = LibUsb.getDeviceDescriptor(device, d);
		if (err == 0) {
			vendor = d.idVendor() & 0xffff;
			product = d.idProduct() & 0xffff;
		}

		final StringBuilder sb = new StringBuilder();
		final String item = vendor != 0 ? toDeviceId(vendor, product) : "";
		sb.append("Bus ").append(bus).append(" Device ").append(address).append(": ID ")
				.append(item);

		final String ind = "    ";
		final DeviceHandle dh = new DeviceHandle();
		err = LibUsb.open(device, dh);
		if (err == 0) {
			try {
				final String man = LibUsb.getStringDescriptor(dh, d.iManufacturer());
				final String prodname = LibUsb.getStringDescriptor(dh, d.iProduct());
				String desc = ind;
				if (prodname != null)
					desc += prodname;
				if (man != null)
					desc += " (" + man + ")";
				if (desc != ind)
					sb.append("\n").append(desc);
			}
			finally {
				LibUsb.close(dh);
			}
		}

		String attach = "";
		final Device parent = LibUsb.getParent(device);
		if (parent != null) {
			// ??? not necessary in my understanding of the USB tree structure, the bus
			// has to be the same as the child one's
			final int parentBus = LibUsb.getBusNumber(parent);
			final int parentAddress = LibUsb.getDeviceAddress(parent);
			attach += "Parent Hub " + parentBus + ":" + parentAddress;
		}
		final int port = LibUsb.getPortNumber(device);
		if (port != 0)
			attach += ", attached at port " + port;
		if (!attach.isEmpty())
			sb.append("\n").append(ind).append(attach);

		final int speed = LibUsb.getDeviceSpeed(device);
		if (speed != LibUsb.SPEED_UNKNOWN)
			sb.append("\n").append(ind).append(DescriptorUtils.getSpeedName(speed))
					.append(" Speed USB");
		return sb.toString();
	}

	private static String toDeviceId(final int vendorId, final int productId)
	{
		return String.format("%04x:%04x", vendorId, productId);
	}

	private static long toUnsigned(final byte[] data)
	{
		if (data.length == 1)
			return (data[0] & 0xff);
		if (data.length == 2)
			return (data[0] & 0xff) << 8 | data[1] & 0xff;
		return (data[0] & 0xff) << 24 | (data[1] & 0xff) << 16 | (data[2] & 0xff) << 8 | data[3]
				& 0xff;
	}
}
