/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.common.node;

import lombok.Getter;
import lombok.ToString;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import me.lucko.luckperms.api.MetaUtils;
import me.lucko.luckperms.api.Node;
import me.lucko.luckperms.api.context.ContextSet;
import me.lucko.luckperms.api.context.ImmutableContextSet;
import me.lucko.luckperms.api.context.MutableContextSet;
import me.lucko.luckperms.common.utils.DateUtil;
import me.lucko.luckperms.common.utils.PatternCache;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkState;

/**
 * An immutable implementation of {@link Node}.
 */
@ToString(of = {"permission", "value", "override", "server", "world", "expireAt", "contexts"})
public final class ImmutableNode implements Node {
    private static final int NODE_SEPARATOR_CHAR = Character.getNumericValue('.');

    private static final String[] PERMISSION_DELIMS = new String[]{"/", "-", "$", "(", ")", "=", ","};
    private static final String[] SERVER_WORLD_DELIMS = new String[]{"/", "-"};
    private static final Splitter META_SPLITTER = Splitter.on(PatternCache.compileDelimitedMatcher(".", "\\")).limit(2);


    /*
     * NODE STATE
     *
     * This are the actual node parameters, and are
     * basically what this class wraps.
     */

    @Getter
    private final String permission;

    private final boolean value;

    @Getter
    private boolean override;

    // nullable
    private final String server;
    // nullable
    private final String world;

    // 0L for no expiry
    private final long expireAt;

    @Getter
    private final ImmutableContextSet contexts;

    @Getter
    private final ImmutableContextSet fullContexts;

    /*
     * CACHED STATE
     *
     * These values are based upon the node state above, and are stored here
     * to make node comparison and manipulation faster.
     *
     * This increases the memory footprint of this class by a bit, but it is
     * worth it for the gain in speed.
     *
     * The methods on this class are called v. frequently.
     */

    // storing optionals as a field type is usually a bad idea, however, the
    // #getServer and #getWorld methods are called frequently when comparing nodes.
    // without caching these values, it creates quite a bit of object churn
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private final Optional<String> optServer;
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private final Optional<String> optWorld;

    private final int hashCode;

    private final boolean isGroup;
    private String groupName;

    private final boolean isWildcard;
    private final int wildcardLevel;

    private final boolean isMeta;
    private Map.Entry<String, String> meta;

    private final boolean isPrefix;
    private Map.Entry<Integer, String> prefix;

    private final boolean isSuffix;
    private Map.Entry<Integer, String> suffix;

    private final List<String> resolvedShorthand;

    private String serializedNode = null;

    /**
     * Make an immutable node instance
     *
     * @param permission the actual permission node
     * @param value      the value (if it's *not* negated)
     * @param expireAt   the time when the node will expire
     * @param server     the server this node applies on
     * @param world      the world this node applies on
     * @param contexts   any additional contexts applying to this node
     */
    @SuppressWarnings("deprecation")
    ImmutableNode(String permission, boolean value, boolean override, long expireAt, String server, String world, ContextSet contexts) {
        if (permission == null || permission.equals("")) {
            throw new IllegalArgumentException("Empty permission");
        }

        // standardize server/world values.
        if (server != null) {
            server = server.toLowerCase();
        }
        if (world != null) {
            world = world.toLowerCase();
        }
        if (server != null && (server.equals("global") || server.equals(""))) {
            server = null;
        }
        if (world != null && (world.equals("global") || world.equals(""))) {
            world = null;
        }

        this.permission = NodeFactory.unescapeDelimiters(permission, PERMISSION_DELIMS).intern();
        this.value = value;
        this.override = override;
        this.expireAt = expireAt;
        this.server = internString(NodeFactory.unescapeDelimiters(server, SERVER_WORLD_DELIMS));
        this.world = internString(NodeFactory.unescapeDelimiters(world, SERVER_WORLD_DELIMS));
        this.contexts = contexts == null ? ContextSet.empty() : contexts.makeImmutable();

        String lowerCasePermission = this.permission.toLowerCase();

        // Setup state
        isGroup = lowerCasePermission.startsWith("group.");
        if (isGroup) {
            groupName = lowerCasePermission.substring("group.".length()).intern();
        }

        isWildcard = this.permission.endsWith(".*");
        wildcardLevel = this.permission.chars().filter(num -> num == NODE_SEPARATOR_CHAR).sum();

        isMeta = NodeFactory.isMetaNode(this.permission);
        if (isMeta) {
            List<String> metaPart = META_SPLITTER.splitToList(this.permission.substring("meta.".length()));
            meta = Maps.immutableEntry(MetaUtils.unescapeCharacters(metaPart.get(0)).intern(), MetaUtils.unescapeCharacters(metaPart.get(1)).intern());
        }

        isPrefix = NodeFactory.isPrefixNode(this.permission);
        if (isPrefix) {
            List<String> prefixPart = META_SPLITTER.splitToList(this.permission.substring("prefix.".length()));
            Integer i = Integer.parseInt(prefixPart.get(0));
            prefix = Maps.immutableEntry(i, MetaUtils.unescapeCharacters(prefixPart.get(1)).intern());
        }

        isSuffix = NodeFactory.isSuffixNode(this.permission);
        if (isSuffix) {
            List<String> suffixPart = META_SPLITTER.splitToList(this.permission.substring("suffix.".length()));
            Integer i = Integer.parseInt(suffixPart.get(0));
            suffix = Maps.immutableEntry(i, MetaUtils.unescapeCharacters(suffixPart.get(1)).intern());
        }

        resolvedShorthand = ImmutableList.copyOf(ShorthandParser.parseShorthand(getPermission()));

        if (this.server != null || this.world != null) {
            MutableContextSet fullContexts = this.contexts.mutableCopy();
            if (this.server != null) {
                fullContexts.add("server", this.server);
            }
            if (this.world != null) {
                fullContexts.add("world", this.world);
            }

            this.fullContexts = fullContexts.makeImmutable();
        } else {
            this.fullContexts = this.contexts;
        }

        this.optServer = Optional.ofNullable(this.server);
        this.optWorld = Optional.ofNullable(this.world);
        this.hashCode = calculateHashCode();
    }

    @Override
    public boolean getValuePrimitive() {
        return value;
    }

    @Override
    public Optional<String> getServer() {
        return optServer;
    }

    @Override
    public Optional<String> getWorld() {
        return optWorld;
    }

    @Override
    public boolean isServerSpecific() {
        return server != null;
    }

    @Override
    public boolean isWorldSpecific() {
        return world != null;
    }

    @Override
    public boolean appliesGlobally() {
        return server == null && world == null && contexts.isEmpty();
    }

    @Override
    public boolean hasSpecificContext() {
        return server != null || world != null || !contexts.isEmpty();
    }

    @Override
    public boolean isTemporary() {
        return expireAt != 0L;
    }

    @Override
    public long getExpiryUnixTime() {
        checkState(isTemporary(), "Node does not have an expiry time.");
        return expireAt;
    }

    @Override
    public Date getExpiry() {
        checkState(isTemporary(), "Node does not have an expiry time.");
        return new Date(expireAt * 1000L);
    }

    @Override
    public long getSecondsTilExpiry() {
        checkState(isTemporary(), "Node does not have an expiry time.");
        return expireAt - DateUtil.unixSecondsNow();
    }

    @Override
    public boolean hasExpired() {
        return isTemporary() && expireAt < DateUtil.unixSecondsNow();
    }

    @Override
    public boolean isGroupNode() {
        return isGroup;
    }

    @Override
    public String getGroupName() {
        checkState(isGroupNode(), "Node is not a group node");
        return groupName;
    }

    @Override
    public boolean isWildcard() {
        return isWildcard;
    }

    @Override
    public int getWildcardLevel() {
        return wildcardLevel;
    }

    @Override
    public boolean isMeta() {
        return isMeta;
    }

    @Override
    public Map.Entry<String, String> getMeta() {
        checkState(isMeta(), "Node is not a meta node");
        return meta;
    }

    @Override
    public boolean isPrefix() {
        return isPrefix;
    }

    @Override
    public Map.Entry<Integer, String> getPrefix() {
        checkState(isPrefix(), "Node is not a prefix node");
        return prefix;
    }

    @Override
    public boolean isSuffix() {
        return isSuffix;
    }

    @Override
    public Map.Entry<Integer, String> getSuffix() {
        checkState(isSuffix(), "Node is not a suffix node");
        return suffix;
    }

    @Override
    public boolean shouldApply(boolean includeGlobal, boolean includeGlobalWorld, String server, String world, ContextSet context, boolean applyRegex) {
        return shouldApplyOnServer(server, includeGlobal, applyRegex) && shouldApplyOnWorld(world, includeGlobalWorld, applyRegex) && shouldApplyWithContext(context, false);
    }

    @Override
    public boolean shouldApplyOnServer(String server, boolean includeGlobal, boolean applyRegex) {
        if (server == null || server.equals("") || server.equalsIgnoreCase("global")) {
            return !isServerSpecific();
        }

        return isServerSpecific() ? shouldApply(server, applyRegex, this.server) : includeGlobal;
    }

    @Override
    public boolean shouldApplyOnWorld(String world, boolean includeGlobal, boolean applyRegex) {
        if (world == null || world.equals("") || world.equalsIgnoreCase("null")) {
            return !isWorldSpecific();
        }

        return isWorldSpecific() ? shouldApply(world, applyRegex, this.world) : includeGlobal;
    }

    @Override
    public boolean shouldApplyWithContext(ContextSet context, boolean worldAndServer) {
        if (contexts.isEmpty() && !isServerSpecific() && !isWorldSpecific()) {
            return true;
        }

        if (worldAndServer) {
            if (isWorldSpecific()) {
                if (context == null) return false;
                if (!context.hasIgnoreCase("world", world)) return false;
            }

            if (isServerSpecific()) {
                if (context == null) return false;
                if (!context.hasIgnoreCase("server", server)) return false;
            }
        }

        if (!contexts.isEmpty()) {
            if (context == null) return false;

            for (Map.Entry<String, String> c : contexts.toSet()) {
                if (!context.hasIgnoreCase(c.getKey(), c.getValue())) return false;
            }
        }

        return true;
    }

    @Override
    public boolean shouldApplyWithContext(ContextSet context) {
        return shouldApplyWithContext(context, true);
    }

    @Override
    public boolean shouldApplyOnAnyServers(List<String> servers, boolean includeGlobal) {
        for (String s : servers) {
            if (shouldApplyOnServer(s, includeGlobal, false)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean shouldApplyOnAnyWorlds(List<String> worlds, boolean includeGlobal) {
        for (String s : worlds) {
            if (shouldApplyOnWorld(s, includeGlobal, false)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public List<String> resolveWildcard(List<String> possibleNodes) {
        if (!isWildcard() || possibleNodes == null) {
            return Collections.emptyList();
        }

        String match = getPermission().substring(0, getPermission().length() - 2);
        return possibleNodes.stream().filter(pn -> pn.startsWith(match)).collect(Collectors.toList());
    }

    @Override
    public List<String> resolveShorthand() {
        return resolvedShorthand;
    }

    @Override
    public synchronized String toSerializedNode() {
        if (serializedNode == null) {
            serializedNode = calculateSerializedNode();
        }
        return serializedNode;
    }

    private String calculateSerializedNode() {
        StringBuilder builder = new StringBuilder();

        if (server != null) {
            builder.append(NodeFactory.escapeDelimiters(server, SERVER_WORLD_DELIMS));
            if (world != null) builder.append("-").append(NodeFactory.escapeDelimiters(world, SERVER_WORLD_DELIMS));
            builder.append("/");
        } else {
            if (world != null) builder.append("global-").append(NodeFactory.escapeDelimiters(world, SERVER_WORLD_DELIMS)).append("/");
        }

        if (!contexts.isEmpty()) {
            builder.append("(");
            for (Map.Entry<String, String> entry : contexts.toSet()) {
                builder.append(NodeFactory.escapeDelimiters(entry.getKey(), "=", "(", ")", ",")).append("=").append(NodeFactory.escapeDelimiters(entry.getValue(), "=", "(", ")", ",")).append(",");
            }
            builder.deleteCharAt(builder.length() - 1);
            builder.append(")");
        }

        builder.append(NodeFactory.escapeDelimiters(permission, "/", "-", "$", "(", ")", "=", ","));
        if (expireAt != 0L) builder.append("$").append(expireAt);
        return builder.toString();
    }

    @SuppressWarnings("StringEquality")
    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof Node)) return false;
        final Node other = (Node) o;

        if (this.permission != other.getPermission()) return false;
        if (this.value != other.getValuePrimitive()) return false;
        if (this.override != other.isOverride()) return false;

        final String thisServer = this.server;
        final String otherServer = other.getServer().orElse(null);
        if (thisServer == null ? otherServer != null : !thisServer.equals(otherServer)) return false;

        final String thisWorld = this.world;
        final String otherWorld = other.getWorld().orElse(null);
        if (thisWorld == null ? otherWorld != null : !thisWorld.equals(otherWorld)) return false;

        final long otherExpireAt = other.isTemporary() ? other.getExpiryUnixTime() : 0L;
        return this.expireAt == otherExpireAt && this.getContexts().equals(other.getContexts());
    }

    @Override
    public int hashCode() {
        return this.hashCode;
    }

    private int calculateHashCode() {
        final int PRIME = 59;
        int result = 1;

        result = result * PRIME + this.permission.hashCode();
        result = result * PRIME + (this.value ? 79 : 97);
        result = result * PRIME + (this.override ? 79 : 97);
        result = result * PRIME + (this.server == null ? 43 : this.server.hashCode());
        result = result * PRIME + (this.world == null ? 43 : this.world.hashCode());
        result = result * PRIME + (int) (this.expireAt >>> 32 ^ this.expireAt);
        result = result * PRIME + this.contexts.hashCode();

        return result;
    }

    @SuppressWarnings("StringEquality")
    @Override
    public boolean equalsIgnoringValue(Node other) {
        if (this.permission != other.getPermission()) return false;
        if (this.override != other.isOverride()) return false;

        final String thisServer = this.server;
        final String otherServer = other.getServer().orElse(null);
        if (thisServer == null ? otherServer != null : !thisServer.equals(otherServer)) return false;

        final String thisWorld = this.world;
        final String otherWorld = other.getWorld().orElse(null);
        if (thisWorld == null ? otherWorld != null : !thisWorld.equals(otherWorld)) return false;

        final long otherExpireAt = other.isTemporary() ? other.getExpiryUnixTime() : 0L;
        return this.expireAt == otherExpireAt && this.getContexts().equals(other.getContexts());
    }

    @SuppressWarnings("StringEquality")
    @Override
    public boolean almostEquals(Node other) {
        if (this.permission != other.getPermission()) return false;
        if (this.override != other.isOverride()) return false;

        final String thisServer = this.server;
        final String otherServer = other.getServer().orElse(null);
        if (thisServer == null ? otherServer != null : !thisServer.equals(otherServer))
            return false;

        final String thisWorld = this.world;
        final String otherWorld = other.getWorld().orElse(null);
        return (thisWorld == null ? otherWorld == null : thisWorld.equals(otherWorld)) &&
                this.isTemporary() == other.isTemporary() &&
                this.getContexts().equals(other.getContexts());

    }

    @SuppressWarnings("StringEquality")
    @Override
    public boolean equalsIgnoringValueOrTemp(Node other) {
        if (this.permission != other.getPermission()) return false;
        if (this.override != other.isOverride()) return false;

        final String thisServer = this.server;
        final String otherServer = other.getServer().orElse(null);
        if (thisServer == null ? otherServer != null : !thisServer.equals(otherServer))
            return false;

        final String thisWorld = this.world;
        final String otherWorld = other.getWorld().orElse(null);
        return (thisWorld == null ? otherWorld == null : thisWorld.equals(otherWorld)) &&
                this.getContexts().equals(other.getContexts());
    }

    @Override
    public Boolean setValue(Boolean value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getKey() {
        return getPermission();
    }

    private static boolean shouldApply(String str, boolean applyRegex, String thisStr) {
        if (str.equalsIgnoreCase(thisStr)) {
            return true;
        }

        Set<String> expandedStr = ShorthandParser.parseShorthand(str, false);
        Set<String> expandedThisStr = ShorthandParser.parseShorthand(thisStr, false);

        if (str.toLowerCase().startsWith("r=") && applyRegex) {
            Pattern p = PatternCache.compile(str.substring(2));
            if (p == null) {
                return false;
            }

            for (String s : expandedThisStr) {
                if (p.matcher(s).matches()) return true;
            }
            return false;
        }

        if (thisStr.toLowerCase().startsWith("r=") && applyRegex) {
            Pattern p = PatternCache.compile(thisStr.substring(2));
            if (p == null) return false;

            for (String s : expandedStr) {
                if (p.matcher(s).matches()) return true;
            }
            return false;
        }

        if (expandedStr.size() <= 1 && expandedThisStr.size() <= 1) return false;

        for (String t : expandedThisStr) {
            for (String s : expandedStr) {
                if (t.equalsIgnoreCase(s)) return true;
            }
        }
        return false;
    }

    private static String internString(String s) {
        return s == null ? null : s.intern();
    }

}
