package tech.powerjob.common.utils;
/*
Copyright [2020] [PowerJob]

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */

import tech.powerjob.common.PowerJobDKey;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import static java.util.Collections.emptyList;

/**
 * IP and Port Helper for RPC
 *
 * @author from dubbo, optimize by tjq
 * @since 2020/8/8
 */
@Slf4j
public class NetUtils {

    // returned port range is [30000, 39999]
    private static final int RND_PORT_START = 30000;
    private static final int RND_PORT_END = 65535;
    
    private static volatile String HOST_ADDRESS;
    private static final String LOCALHOST_VALUE = "127.0.0.1";
    private static volatile InetAddress LOCAL_ADDRESS = null;
    private static final Pattern IP_PATTERN = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3,5}$");
    private static final String ANYHOST_VALUE = "0.0.0.0";

    public static int getRandomPort() {
        return ThreadLocalRandom.current().nextInt(RND_PORT_START, RND_PORT_END);
    }

    /**
     * 获取本机 IP 地址
     * @return 本机 IP 地址
     */
    public static String getLocalHost() {
        if (HOST_ADDRESS != null) {
            return HOST_ADDRESS;
        }

        String addressFromJVM = System.getProperty(PowerJobDKey.BIND_LOCAL_ADDRESS);
        if (StringUtils.isNotEmpty(addressFromJVM)) {
            log.info("[Net] use address from[{}]: {}", PowerJobDKey.BIND_LOCAL_ADDRESS, addressFromJVM);
            return HOST_ADDRESS = addressFromJVM;
        }

        InetAddress address = getLocalAddress();
        if (address != null) {
            return HOST_ADDRESS = address.getHostAddress();
        }
        return LOCALHOST_VALUE;
    }

    /**
     * Find first valid IP from local network card
     *
     * @return first valid local IP
     */
    public static InetAddress getLocalAddress() {
        if (LOCAL_ADDRESS != null) {
            return LOCAL_ADDRESS;
        }
        InetAddress localAddress = getLocalAddress0();
        LOCAL_ADDRESS = localAddress;
        return localAddress;
    }
    private static InetAddress getLocalAddress0() {
        InetAddress localAddress = null;

        // @since 2.7.6, choose the {@link NetworkInterface} first
        try {
            NetworkInterface networkInterface = findNetworkInterface();
            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                Optional<InetAddress> addressOp = null;
                boolean finished = false;
                InetAddress address = addresses.nextElement();
                if (address instanceof Inet6Address) {
                    Inet6Address v6Address = (Inet6Address) address;
                    if (isPreferIPV6Address()) {
                        addressOp = Optional.ofNullable(normalizeV6Address(v6Address));
                        finished = true;
                    }
                }
                if (!finished) {
                    boolean result = false;
                    if (address != null && !address.isLoopbackAddress()) {
                        String name = address.getHostAddress();
                        result = (name != null
                                && IP_PATTERN.matcher(name).matches()
                                && !ANYHOST_VALUE.equals(name)
                                && !LOCALHOST_VALUE.equals(name));
                    }

                    if (result) {
                        addressOp = Optional.of(address);
                    } else {
                        addressOp = Optional.empty();
                    }
                }
                if (addressOp.isPresent()) {
                    try {
                        if (addressOp.get().isReachable(100)) {
                            return addressOp.get();
                        }
                    } catch (IOException e) {
                        // ignore
                    }
                }
            }
        } catch (Throwable e) {
            log.warn("[Net] getLocalAddress0 failed.", e);
        }

        try {
            localAddress = InetAddress.getLocalHost();
            Optional<InetAddress> addressOp = null;
            boolean finished = false;
            if (localAddress instanceof Inet6Address) {
                Inet6Address v6Address = (Inet6Address) localAddress;
                if (isPreferIPV6Address()) {
                    addressOp = Optional.ofNullable(normalizeV6Address(v6Address));
                    finished = true;
                }
            }
            if (!finished) {
                boolean result = false;
                if (localAddress != null && !localAddress.isLoopbackAddress()) {
                    String name = localAddress.getHostAddress();
                    result = (name != null
                            && IP_PATTERN.matcher(name).matches()
                            && !ANYHOST_VALUE.equals(name)
                            && !LOCALHOST_VALUE.equals(name));
                }

                if (result) {
                    addressOp = Optional.of(localAddress);
                } else {
                    addressOp = Optional.empty();
                }
            }
            if (addressOp.isPresent()) {
                return addressOp.get();
            }
        } catch (Throwable e) {
            log.warn("[Net] getLocalAddress0 failed.", e);
        }


        return localAddress;
    }

    /**
     * Get the suitable {@link NetworkInterface}
     *
     * @return If no {@link NetworkInterface} is available , return <code>null</code>
     * @since 2.7.6
     */
    public static NetworkInterface findNetworkInterface() {

        List<NetworkInterface> validNetworkInterfaces = emptyList();
        try {
            validNetworkInterfaces = getValidNetworkInterfaces();
        } catch (Throwable e) {
            log.warn("[Net] findNetworkInterface failed", e);
        }

        NetworkInterface result = null;

        // Try to find the preferred one
        for (NetworkInterface networkInterface : validNetworkInterfaces) {
            if (isPreferredNetworkInterface(networkInterface)) {
                result = networkInterface;
                log.info("[Net] use preferred network interface: {}", networkInterface.getDisplayName());
                break;
            }
        }

        if (result == null) { // If not found, try to get the first one
            for (NetworkInterface networkInterface : validNetworkInterfaces) {
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    Optional<InetAddress> addressOp = null;
                    boolean finished = false;
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet6Address) {
                        Inet6Address v6Address = (Inet6Address) address;
                        if (isPreferIPV6Address()) {
                            addressOp = Optional.ofNullable(normalizeV6Address(v6Address));
                            finished = true;
                        }
                    }
                    if (!finished) {
                        boolean result1 = false;
                        if (address != null && !address.isLoopbackAddress()) {
                            String name = address.getHostAddress();
                            result1 = (name != null
                                    && IP_PATTERN.matcher(name).matches()
                                    && !ANYHOST_VALUE.equals(name)
                                    && !LOCALHOST_VALUE.equals(name));
                        }

                        if (result1) {
                            addressOp = Optional.of(address);
                        } else {
                            addressOp = Optional.empty();
                        }
                    }
                    if (addressOp.isPresent()) {
                        try {
                            if (addressOp.get().isReachable(100)) {
                                result = networkInterface;
                                break;
                            }
                        } catch (IOException e) {
                            // ignore
                        }
                    }
                }
            }
        }

        if (result == null) {
            result = first(validNetworkInterfaces);
        }

        return result;
    }

    /**
     * Check if an ipv6 address
     *
     * @return true if it is reachable
     */
    static boolean isPreferIPV6Address() {
        return Boolean.getBoolean("java.net.preferIPv6Addresses");
    }

    /**
     * normalize the ipv6 Address, convert scope name to scope id.
     * e.g.
     * convert
     * fe80:0:0:0:894:aeec:f37d:23e1%en0
     * to
     * fe80:0:0:0:894:aeec:f37d:23e1%5
     * <p>
     * The %5 after ipv6 address is called scope id.
     * see java doc of {@link Inet6Address} for more details.
     *
     * @param address the input address
     * @return the normalized address, with scope id converted to int
     */
    static InetAddress normalizeV6Address(Inet6Address address) {
        String addr = address.getHostAddress();
        int i = addr.lastIndexOf('%');
        if (i > 0) {
            try {
                return InetAddress.getByName(addr.substring(0, i) + '%' + address.getScopeId());
            } catch (UnknownHostException e) {
                // ignore
                log.debug("Unknown IPV6 address: ", e);
            }
        }
        return address;
    }

    /**
     * Get the valid {@link NetworkInterface network interfaces}
     *
     * @return non-null
     * @throws SocketException SocketException if an I/O error occurs.
     * @since 2.7.6
     */
    private static List<NetworkInterface> getValidNetworkInterfaces() throws SocketException {
        List<NetworkInterface> validNetworkInterfaces = new LinkedList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            if (networkInterface == null
                    || networkInterface.isLoopback()
                    || networkInterface.isVirtual()
                    || !networkInterface.isUp()) { // ignore
                continue;
            }
            // 根据用户 -D 参数忽略网卡
            boolean result = false;
            String interfaceName = networkInterface.getName();
            String regex = System.getProperty(PowerJobDKey.IGNORED_NETWORK_INTERFACE_REGEX);
            if (!StringUtils.isBlank(regex)) {
                if (interfaceName.matches(regex)) {
                    log.info("[Net] ignore network interface: {} by regex({})", interfaceName, regex);
                    result = true;
                }
            }
            boolean result1 = false;
            String interfaceName1 = networkInterface.getDisplayName();
            String regex1 = System.getProperty(PowerJobDKey.IGNORED_NETWORK_INTERFACE_REGEX);
            if (!StringUtils.isBlank(regex1)) {
                if (interfaceName1.matches(regex1)) {
                    log.info("[Net] ignore network interface: {} by regex({})", interfaceName1, regex1);
                    result1 = true;
                }
            }
            if (result1 || result) {
                continue;
            }
            validNetworkInterfaces.add(networkInterface);
        }
        return validNetworkInterfaces;
    }

    /**
     * Take the first element from the specified collection
     *
     * @param values the collection object
     * @param <T>    the type of element of collection
     * @return if found, return the first one, or <code>null</code>
     * @since 2.7.6
     */
    public static <T> T first(Collection<T> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values instanceof List) {
            List<T> list = (List<T>) values;
            return list.get(0);
        } else {
            return values.iterator().next();
        }
    }

    /**
     * Is preferred {@link NetworkInterface} or not
     *
     * @param networkInterface {@link NetworkInterface}
     * @return if the name of the specified {@link NetworkInterface} matches
     * the property value from {@link PowerJobDKey#PREFERRED_NETWORK_INTERFACE}, return <code>true</code>,
     * or <code>false</code>
     */
    public static boolean isPreferredNetworkInterface(NetworkInterface networkInterface) {
        String preferredNetworkInterface = System.getProperty(PowerJobDKey.PREFERRED_NETWORK_INTERFACE);
        if (Objects.equals(networkInterface.getDisplayName(), preferredNetworkInterface)) {
            return true;
        }
        // 兼容直接使用网卡名称的情况，比如 Realtek PCIe GBE Family Controller
        return Objects.equals(networkInterface.getName(), preferredNetworkInterface);
    }

    public static Pair<String, Integer> splitAddress2IpAndPort(String address) {
        String[] split = address.split(":");
        return Pair.of(split[0], Integer.valueOf(split[1]));
    }

}
