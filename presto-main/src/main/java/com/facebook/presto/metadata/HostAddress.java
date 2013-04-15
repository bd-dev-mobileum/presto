package com.facebook.presto.metadata;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An immutable representation of a host and port.
 * <p/>
 * <p>Example usage:
 * <pre>
 * HostAndPort hp = HostAndPort.fromString("[2001:db8::1]")
 *     .withDefaultPort(80)
 *     .requireBracketsForIPv6();
 * hp.getHostText();  // returns "2001:db8::1"
 * hp.getPort();      // returns 80
 * hp.toString();     // returns "[2001:db8::1]:80"
 * </pre>
 * <p/>
 * <p>Here are some examples of recognized formats:
 * <ul>
 * <li>example.com
 * <li>example.com:80
 * <li>192.0.2.1
 * <li>192.0.2.1:80
 * <li>[2001:db8::1]     - {@link #getHostText()} omits brackets
 * <li>[2001:db8::1]:80  - {@link #getHostText()} omits brackets
 * <li>2001:db8::1       - Use {@link #requireBracketsForIPv6()} to prohibit this
 * </ul>
 * <p/>
 * <p>Note that this is not an exhaustive list, because these methods are only
 * concerned with brackets, colons, and port numbers.  Full validation of the
 * host field (if desired) is the caller's responsibility.
 *
 * @author Paul Marks
 * @since 10.0
 */
public class HostAddress
{
    /**
     * Magic value indicating the absence of a port number.
     */
    private static final int NO_PORT = -1;

    /**
     * Hostname, IPv4/IPv6 literal, or unvalidated nonsense.
     */
    private final String host;

    /**
     * Validated port number in the range [0..65535], or NO_PORT
     */
    private final int port;

    /**
     * True if the parsed host has colons, but no surrounding brackets.
     */
    private final boolean hasBracketlessColons;

    private HostAddress(String host, int port, boolean hasBracketlessColons)
    {
        this.host = host;
        this.port = port;
        this.hasBracketlessColons = hasBracketlessColons;
    }

    /**
     * Returns the portion of this {@code HostAddress} instance that should
     * represent the hostname or IPv4/IPv6 literal.
     * <p/>
     * <p>A successful parse does not imply any degree of sanity in this field.
     * For additional validation, see the {@link com.google.common.net.HostSpecifier} class.
     */
    public String getHostText()
    {
        return host;
    }

    /**
     * Return true if this instance has a defined port.
     */
    public boolean hasPort()
    {
        return port >= 0;
    }

    /**
     * Get the current port number, failing if no port is defined.
     *
     * @return a validated port number, in the range [0..65535]
     * @throws IllegalStateException if no port is defined.  You can use
     * {@link #withDefaultPort(int)} to prevent this from occurring.
     */
    public int getPort()
    {
        if (!hasPort()) {
            throw new IllegalStateException("no port");
        }
        return port;
    }

    /**
     * Returns the current port number, with a default if no port is defined.
     */
    public int getPortOrDefault(int defaultPort)
    {
        return hasPort() ? port : defaultPort;
    }

    /**
     * Build a HostAddress instance from separate host and port values.
     * <p/>
     * <p>Note: Non-bracketed IPv6 literals are allowed.
     * Use {@link #requireBracketsForIPv6()} to prohibit these.
     *
     * @param host the host string to parse.  Must not contain a port number.
     * @param port a port number from [0..65535]
     * @return if parsing was successful, a populated HostAddress object.
     * @throws IllegalArgumentException if {@code host} contains a port number,
     * or {@code port} is out of range.
     */
    public static HostAddress fromParts(String host, int port)
    {
        if (!isValidPort(port)) {
            throw new IllegalArgumentException("Port is invalid: " + port);
        }
        HostAddress parsedHost = fromString(host);
        if (parsedHost.hasPort()) {
            throw new IllegalArgumentException("host contains a port declaration: " + host);
        }
        return new HostAddress(parsedHost.host, port, parsedHost.hasBracketlessColons);
    }

    private static final Pattern BRACKET_PATTERN = Pattern.compile("^\\[(.*:.*)\\](?::(\\d*))?$");

    /**
     * Split a freeform string into a host and port, without strict validation.
     * <p/>
     * Note that the host-only formats will leave the port field undefined.  You
     * can use {@link #withDefaultPort(int)} to patch in a default value.
     *
     * @param hostPortString the input string to parse.
     * @return if parsing was successful, a populated HostAddress object.
     * @throws IllegalArgumentException if nothing meaningful could be parsed.
     */
    @JsonCreator
    public static HostAddress fromString(String hostPortString)
    {
        if (hostPortString == null) {
            throw new NullPointerException("hostPortString is null");
        }
        String host;
        String portString = null;
        boolean hasBracketlessColons = false;

        if (hostPortString.startsWith("[")) {
            // Parse a bracketed host, typically an IPv6 literal.
            Matcher matcher = BRACKET_PATTERN.matcher(hostPortString);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Invalid bracketed host/port: " + hostPortString);
            }
            host = matcher.group(1);
            portString = matcher.group(2);  // could be null
        }
        else {
            int colonPos = hostPortString.indexOf(':');
            if (colonPos >= 0 && hostPortString.indexOf(':', colonPos + 1) == -1) {
                // Exactly 1 colon.  Split into host:port.
                host = hostPortString.substring(0, colonPos);
                portString = hostPortString.substring(colonPos + 1);
            }
            else {
                // 0 or 2+ colons.  Bare hostname or IPv6 literal.
                host = hostPortString;
                hasBracketlessColons = (colonPos >= 0);
            }
        }

        int port = NO_PORT;
        if (portString != null && portString.length() != 0) {
            // Try to parse the whole port string as a number.
            // JDK7 accepts leading plus signs. We don't want to.
            if (portString.startsWith("+")) {
                throw new IllegalArgumentException("Unparseable port number: " + hostPortString);
            }
            try {
                port = Integer.parseInt(portString);
            }
            catch (NumberFormatException e) {
                throw new IllegalArgumentException("Unparseable port number: " + hostPortString);
            }
            if (!isValidPort(port)) {
                throw new IllegalArgumentException("Port number out of range: " + hostPortString);
            }
        }

        return new HostAddress(host, port, hasBracketlessColons);
    }

    public static HostAddress fromUri(URI httpUri)
    {
        String host = httpUri.getHost();
        int port = httpUri.getPort();
        if (port < 0) {
            return fromString(host);
        }
        else {
            return fromParts(host, port);
        }
    }

    /**
     * Provide a default port if the parsed string contained only a host.
     * <p/>
     * You can chain this after {@link #fromString(String)} to include a port in
     * case the port was omitted from the input string.  If a port was already
     * provided, then this method is a no-op.
     *
     * @param defaultPort a port number, from [0..65535]
     * @return a HostAddress instance, guaranteed to have a defined port.
     */
    public HostAddress withDefaultPort(int defaultPort)
    {
        if (!isValidPort(defaultPort)) {
            throw new IllegalArgumentException("Port number out of range: " + defaultPort);
        }

        if (hasPort() || port == defaultPort) {
            return this;
        }
        return new HostAddress(host, defaultPort, hasBracketlessColons);
    }

    /**
     * Generate an error if the host might be a non-bracketed IPv6 literal.
     * <p/>
     * <p>URI formatting requires that IPv6 literals be surrounded by brackets,
     * like "[2001:db8::1]".  Chain this call after {@link #fromString(String)}
     * to increase the strictness of the parser, and disallow IPv6 literals
     * that don't contain these brackets.
     * <p/>
     * <p>Note that this parser identifies IPv6 literals solely based on the
     * presence of a colon.  To perform actual validation of IP addresses, see
     * the {@link com.google.common.net.InetAddresses#forString(String)} method.
     *
     * @return {@code this}, to enable chaining of calls.
     * @throws IllegalArgumentException if bracketless IPv6 is detected.
     */
    public HostAddress requireBracketsForIPv6()
    {
        if (hasBracketlessColons) {
            throw new IllegalArgumentException("Possible bracketless IPv6 literal: " + host);
        }
        return this;
    }

    public InetAddress toInetAddress()
    {
        try {
            return InetAddress.getByName(getHostText());
        }
        catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(host, port, hasBracketlessColons);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final HostAddress other = (HostAddress) obj;
        return Objects.equals(this.host, other.host) &&
                Objects.equals(this.port, other.port) &&
                Objects.equals(this.hasBracketlessColons, other.hasBracketlessColons);
    }

    /**
     * Rebuild the host:port string, including brackets if necessary.
     */
    @JsonValue
    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder(host.length() + 7);
        if (host.indexOf(':') >= 0) {
            builder.append('[').append(host).append(']');
        }
        else {
            builder.append(host);
        }
        if (hasPort()) {
            builder.append(':').append(port);
        }
        return builder.toString();
    }

    /**
     * Return true for valid port numbers.
     */
    private static boolean isValidPort(int port)
    {
        return port >= 0 && port <= 65535;
    }
}
