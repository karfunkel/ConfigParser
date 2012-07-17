/*
 * Copyright 2003-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package groovy.util

import org.codehaus.groovy.syntax.Types
import groovy.transform.InheritConstructors
import java.beans.PropertyChangeSupport
import java.beans.PropertyChangeListener
import org.codehaus.groovy.runtime.InvokerHelper

class ConfigNode implements Writable {
    static final KEYWORDS = Types.getKeywords()
    static final TAB_CHARACTER = '\t'

    protected configuration
    @Delegate
    protected Map map = [:] as LinkedHashMap

    protected Map classMap = [:] as LinkedHashMap
    protected GroovyShell shell = new GroovyShell(ConfigNode.classLoader)

    protected URL configFile
    protected ConfigNode parent
    protected String name

    protected PropertyChangeSupport pcs = new PropertyChangeSupport(this)

    protected ConfigParser parser
    protected Script script

    ConfigNode(String name, ConfigNode parentNode = null, URL file = null, ConfigConfiguration configuration = null, ConfigParser parser = null, Script script = null) {
        this.@name = name
        this.@configuration = configuration
        parent = parentNode
        configFile = file
        this.@parser = parser
        this.@script = script
    }

    protected ConfigConfiguration _getConfiguration() {
        if (!this.@configuration) {
            try {
                return this.@parent?._getConfiguration() ?: new ConfigConfiguration()
            } catch (e) {
                return new ConfigConfiguration()
            }
        }
        return this.@configuration
    }

    def getProperty(String name) {
        return get(name)
    }

    Object get(Object key) {
        boolean newNode = false
        def value
        ConfigParser parser = this.@parser
        Script script = this.@script
        if (parser != null) {
            if (parser.@binding?.containsKey(key)) {
                return parser.@binding[key]
            }
            switch (parser.resolveStrategy) {
                case Closure.DELEGATE_FIRST:
                    try {
                        def val = parser.delegate?.getAt(key)
                        if (val != null)
                            return val
                    } catch (e) { }
                    try {
                        def val = script.invokeMethod("get${key.toString().capitalize()}".toString(), null)
                        if (val != null)
                            return val
                    } catch (e) { }
                    break
                case Closure.DELEGATE_ONLY:
                    try {
                        def val = parser.delegate?.getAt(key)
                        if (val != null)
                            return val
                    } catch (e) {}
                    break
                case Closure.OWNER_FIRST:
                    try {
                        def val = script.invokeMethod("get${key.toString().capitalize()}".toString(), null)
                        if (val != null)
                            return val
                    } catch (e) { }
                    try {
                        def val = parser.delegate?.getAt(key)
                        if (val != null)
                            return val
                    } catch (e) { }
                    break
                case Closure.OWNER_ONLY:
                    try {
                        def val = script.invokeMethod("get${key.toString().capitalize()}".toString(), null)
                        if (val != null)
                            return val
                    } catch (e) {}
                    break
            }
        }
        if (!this.@map.containsKey(key)) {
            // create a new, lazy ConfigNode to enable subproperties, but don't put it into the map
            value = new ConfigNode(key, this, configFile, (ConfigConfiguration) null, (ConfigParser) this.@parser, this.@script)
            newNode = true
        }
        value = value == null ? this.@map.get(key) : value
        if (!(value instanceof ConfigValue)) // could be added manually by mistake
            return prepareResult(this, key, value)
        value = value.value // use inner value
        if (value instanceof ConfigNode || value instanceof ConfigNodeProxy) { // Subnodes or Subnodeproxies
            if (value.@map.isEmpty()) {  // Only return newly created, empty, not linked ConfigNodes to enable subproperties
                return newNode ? value : null
            } else {
                def cfgValue = value.@map.get(_getConfiguration().NODE_VALUE_KEY) // value for nodes with children AND a value
                if (cfgValue != null) {
                    // if we have a nodes with children AND a value
                    def val = cfgValue instanceof ConfigValue ? cfgValue.value : cfgValue // use inner value
                    if (value.@map.size() == 1) {
                        return prepareResult(value, _getConfiguration().NODE_VALUE_KEY, val) // if the node only has a value, but no children, return the value only
                    } else {
                        // Use an unusual Classname to prevent errors
                        def clsName = 'ConfigNode$_' + val.getClass().name.replaceAll(/\./, '_')
                        // Check Proxy-Class-Cache
                        Class cls = classMap[clsName]
                        if (!cls) {
                            // create a Proxy-Class via delegate to enable use from Java
                            // It may not implement the java.lang.Comparable interface to enable x == y
                            def interfaces = (val.getClass().interfaces.name + ['groovy.util.ConfigNodeProxy'] - ['java.lang.Comparable']).join(', ')
                            def src = """package groovy.util.temp
class $clsName implements $interfaces{
    @Delegate(interfaces = false, deprecated = true) protected  ${val.getClass().name} delegate
    protected ConfigNode map
    $clsName(ConfigNode node, ${val.getClass().name} delegate) {
        this.map = node
        this.delegate = delegate
    }
    def propertyMissing(String name) { return map.getProperty(name) }
    def propertyMissing(String name, Object value) { map.setProperty(name, value) }
    Object methodMissing(String name, Object args) { return map.invokeMethod(name, args) }
    boolean equals(Object obj) { return this.delegate.equals(obj)}
    int hashCode() { return this.delegate.hashCode() }
    String toString() { return this.delegate.toString() }
}
$clsName
"""
                            cls = shell.evaluate(src)
                            classMap[clsName] = cls // store the class in the cache
                        }
                        return cls.newInstance(cfgValue.node, val) // return an instance of the ProxyClass
                    }
                }
                return value  // if the node only has children, return the node itself
            }
        } else
            return prepareResult(this, key, value) // if a plain value is stored, return it
    }

    protected def prepareResult(def node, def key, def value) {
        def result
        def config = _getConfiguration()
        if (value instanceof ConfigFactory && config.isFactoryEvaluationEnabled()) {
            result = value.create([this, key])
        } else if (value instanceof ConfigLazy && config.isLazyEvaluationEnabled()) {
            result = value.create([this, key])
            setProperty(key, result)
        } else
            result = value

        return enhanceResult(result, key, node)
    }

    protected Object enhanceResult(result, key, node) {
        if (result == null)
            return result
        if (result instanceof ConfigNode)
            return result
        if (!_getConfiguration().isResultEnhancementEnabled()) {
            if (result.metaClass instanceof EnhancementMetaClass) {
                result.metaClass = result.metaClass.adaptee
            }
            return result
        }
        if (result.metaClass instanceof EnhancementMetaClass)
            return result

        result.metaClass = new EnhancementMetaClass(result, key, node)
        return result
    }

    def getRecursive(String key) {
        def current = this
        def parts = key.split(/\./)
        for (def part : parts) {
            if (part) {
                current = current.getProperty(part)
                if (current == null || (current instanceof ConfigNode && current.isEmpty()))
                    return null
            }
        }
        return current
    }

    void setProperty(String name, Object value) {
        this.put(name, value)
    }

    Object put(Object key, Object value) {
        if (value instanceof ConfigValue) // do not nest ConfigNodeProxy instances, only use its value
            value = value.value // do not ConfigValue instances
        if (value instanceof ConfigNodeProxy) // do not nest ConfigNodeProxy instances, only use its value
            value = value.@map[_getConfiguration().NODE_VALUE_KEY]
        def old = this.@map.get(key)
        def oldValue = old instanceof ConfigValue ? old?.value : old
        if (old == null || !(old instanceof ConfigValue)) { // if the value to store is new, store it
            this.@map.put(key, new ConfigValue(this, key, value))
            // will _fire when value is linked
        } else if (oldValue instanceof ConfigNode && !(value instanceof ConfigNode)) { // if an existing node should get a value itself
            ConfigNode oldNode = oldValue
            oldValue = oldNode.@map[_getConfiguration().NODE_VALUE_KEY]
            oldNode.@map[_getConfiguration().NODE_VALUE_KEY] = new ConfigValue(oldNode, _getConfiguration().NODE_VALUE_KEY, value) // store the new value at the special property
            if (oldValue instanceof ConfigValue)
                oldValue = oldValue.value
            oldNode._firePropertyChange(_getConfiguration().NODE_VALUE_KEY, oldValue, value)
        }
        else {
            old.value = value // if the node already exists, reset its value
            _firePropertyChange(key, oldValue, value)
        }
        return oldValue
    }

    protected void _firePropertyChange(Object property, Object oldValue, Object newValue, boolean force = false) {
        if (_getConfiguration().observable) {
            if (force || oldValue != null) {
                pcs.firePropertyChange(property, oldValue, newValue)
                if (this.@parent != null) {
                    this.@parent?._firePropertyChange("${this.@name}.$property", oldValue, newValue, force)
                }
            }
        }
    }

    void putRecursive(String key, def value) {
        def old = _getConfiguration().resultEnhancementEnabled
        _getConfiguration().resultEnhancementEnabled = true
        def current = this
        def parts = key.split(/\./)
        def last = parts.last()
        parts = parts[0..<(parts.size() - 1)]
        for (def part : parts) {
            if (part)
                current = current.getProperty(part)
        }
        current.setProperty(last, value)
        _getConfiguration().resultEnhancementEnabled = old
    }

    def removeRecursive(String key) {
        def current = this
        def lastNode = null
        def parts = key.split(/\./)
        for (def part : parts) {
            if (part) {
                lastNode = current
                current = current.getProperty(part)
                if (current instanceof ConfigNode && current.isEmpty())
                    return null
            }
        }
        return lastNode.remove(parts.last())
    }

    boolean containsValue(Object value) {
        if (value instanceof ConfigNodeProxy) // use the value of ConfigNodeProxy instances
            value = value.@map[_getConfiguration().NODE_VALUE_KEY]
        return this.@map.containsValue((value instanceof ConfigValue) ? value.value : value)
    }

    Object remove(Object key) {
        def old = this.@map.remove(key)
        old = (old instanceof ConfigValue) ? old.value : old
        _firePropertyChange(key, old, null, true)
        return old
    }

    Collection<Object> values() {
        this.@map.values().collect { (it instanceof ConfigValue) ? it.value : it }
    }

    Set<Map.Entry<Object, Object>> entrySet() {
        def thisObj = this
        this.@map.entrySet().collect { new Entry(it.key, thisObj, it.value) } as Set<Map.Entry<Object, Object>>
    }

    @Override
    Object invokeMethod(String name, Object args) {
        ConfigParser parser = this.@parser
        Script script = this.@script
        if (parser != null) {
            if (parser.@binding?.containsKey(name)) {
                def closure = parser.@binding[name]
                if (closure instanceof Closure)
                    return closure(* args)
            }
        }
        if (args.length == 1 && args[0] instanceof Closure) {
            if (name == 'containsValue')
                return super.invokeMethod(name, args)
            return callClosure(name, args[0])
        } else if (args.length == 2 && args[1] instanceof Closure) {
            if (name == 'setProperty')
                return super.invokeMethod(name, args)
            if (name == 'put')
                return super.invokeMethod(name, args)
            def old = get(name)
            if (old instanceof ConfigNode)
                old.put(_getConfiguration().NODE_VALUE_KEY, args[0])
            else
                put(name, args[0])
            return callClosure(name, args[1])
        } else {
            if (parser != null) {
                switch (parser.resolveStrategy) {
                    case Closure.DELEGATE_FIRST:
                        if (parser.delegate?.metaClass?.respondsTo(parser.delegate, name, args)) {
                            return InvokerHelper.invokeMethod(parser.delegate, name, args)
                        } else if (this.metaClass.respondsTo(this, name, args)) {
                            return super.invokeMethod(name, args)
                        }
                        break
                    case Closure.DELEGATE_ONLY:
                        if (parser.delegate?.metaClass?.respondsTo(parser.delegate, name, args)) {
                            return InvokerHelper.invokeMethod(parser.delegate, name, args)
                        }
                        break
                    case Closure.OWNER_FIRST:
                        if (this.metaClass.respondsTo(this, name, args)) {
                            return super.invokeMethod(name, args)
                        } else if (parser.delegate?.metaClass?.respondsTo(parser.delegate, name, args)) {
                            return InvokerHelper.invokeMethod(parser.delegate, name, args)
                        }
                        break
                    case Closure.OWNER_ONLY:
                        if (this.metaClass.respondsTo(this, name, args)) {
                            return super.invokeMethod(name, args)
                        }
                        break
                }
            }
        }
        def path = this._getPath() ? "${this._getPath()}." : ''
        throw new MissingMethodException("$path$name", this.getClass(), args)
    }

    private callClosure(String name, Closure closure) {
        def value = this.@map.get(name)
        def val = (value instanceof ConfigValue) ? value.value : value
        if (!(val instanceof ConfigNode)) {
            def node = new ConfigNode(name, this, this.@configFile, (ConfigConfiguration) null, this.@parser, this.@script)
            if (val != null) {
                def oldValue = node.@map[_getConfiguration().NODE_VALUE_KEY]
                if (oldValue instanceof ConfigValue)
                    oldValue = oldValue.value
                node.@map[_getConfiguration().NODE_VALUE_KEY] = new ConfigValue(node, _getConfiguration().NODE_VALUE_KEY, val)
                node._firePropertyChange(_getConfiguration().NODE_VALUE_KEY, oldValue, val)
            }
            def oldValue = this.@map[name]
            value = new ConfigValue(this, name, node)
            this.@map.put(name, value)
            _firePropertyChange(name, oldValue, node)
        }
        closure.delegate = new CombinedDelegate(parser?.delegate, value.value, parser?.@binding, parser?.@resolveStrategy)
        closure.resolveStrategy = parser?.resolveStrategy == Closure.DELEGATE_ONLY ? Closure.DELEGATE_ONLY : Closure.DELEGATE_FIRST
        closure.call()
    }

    class Entry implements Map.Entry<Object, Object> {
        ConfigNode node
        Object key
        Object value

        Entry(Object key, ConfigNode node, Object value) {
            this.key = key
            this.node = node
            this.value = value
        }

        Object getValue() {
            value instanceof ConfigValue ? value.value : value
        }

        Object setValue(Object value) {
            def old = this.value instanceof ConfigValue ? this.value.value : this.value
            node.setProperty(key, value instanceof ConfigValue ? value.value : value)
            this.value = node.getProperty(key)
            return old
        }
    }

    /**
     * Converts this ConfigNode into a the java.util.Properties format, flattening the tree structure beforehand
     * @return A java.util.Properties instance
     */
    Properties toProperties() {
        Properties props = new Properties()
        flatten(props)
        props = convertValuesToString(props)
        return props
    }

    /**
     * Converts this ConfigNode into the java.util.Properties format, flatten the tree and prefixing all entries with the given prefix
     * @param prefix The prefix to append before property entries
     * @return A java.util.Properties instance
     */
    Properties toProperties(String prefix) {
        def props = new Properties()
        populate("${prefix}.", props, this)
        props = convertValuesToString(props)
        return props
    }

    /**
     * A ConfigNode is a tree structure consisting of nested maps. This flattens the maps into
     * a single level structure like a properties file
     */
    Map flatten() {
        return flatten(null)
    }

    /**
     * Flattens this ConfigNode populating the results into the target Map
     *
     * @see ConfigNode#flatten()
     */
    Map flatten(Map target) {
        if (target == null)
            target = new ConfigNode('@', (ConfigNode) null, this.@configFile, _getConfiguration().clone(), this.@parser, this.@script)
        populate("", target, this)
        return target
    }

    private Properties convertValuesToString(Map props) {
        Properties newProps = new Properties()
        for (def e : props) {
            newProps[e.key] = e.value?.toString()
        }
        return newProps
    }

    private populate(String prefix, Map target, Map map) {
        map.each { key, value ->
            if (value instanceof ConfigValue)
                value = value.value
            if (value instanceof Map) {
                populate(prefix + "${key}.", target, value)
            }
            else {
                try {
                    def pf = prefix
                    if (key == _getConfiguration().NODE_VALUE_KEY) {
                        key = ''
                        pf = prefix.substring(0, prefix.size() - 1)
                    }
                    target[pf + key] = value
                }
                catch (java.lang.NullPointerException e) {
                    // it is idiotic story but if target map doesn't allow null values (like Hashtable)
                    // we can't do too much
                }
            }
        }
    }

    /**
     * Merges the given map with this ConfigNode overriding any matching configuration entries in this ConfigNode
     *
     * @param other The ConfigNode to merge with
     * @return The result of the merge
     */
    Map merge(ConfigNode other) {
        return doMerge(this, other)
    }

    private doMerge(ConfigNode target, ConfigNode other) {
        for (def entry : other.@map) {
            def value = entry.value
            if (value instanceof ConfigValue)
                value = value.value
            def configEntry = target.@map[entry.key]
            if (configEntry instanceof ConfigValue)
                configEntry = configEntry.value
            if (configEntry == null) {
                def oldValue = target.@map[entry.key]
                target.@map[entry.key] = new ConfigValue(target, entry.key, value, false)
                target._firePropertyChange(entry.key, oldValue, value)
            } else {
                if (configEntry instanceof ConfigNode) {
                    if (value instanceof ConfigNode) {
                        doMerge(configEntry, value)
                    } else {
                        def oldValue = configEntry[_getConfiguration().NODE_VALUE_KEY]
                        configEntry[_getConfiguration().NODE_VALUE_KEY] = new ConfigValue(configEntry, _getConfiguration().NODE_VALUE_KEY, value, false)
                        configEntry._firePropertyChange(_getConfiguration().NODE_VALUE_KEY, oldValue, value)
                    }
                }
                else {
                    def oldValue = target.@map[entry.key]
                    target.@map[entry.key] = new ConfigValue(target, entry.key, value, false)
                    target._firePropertyChange(entry.key, oldValue, value)
                }
            }
        }
        return target
    }

    /**
     * Writes this ConfigNode into a String serialized representation which can later be parsed back using the parse()
     * method
     *
     * @see groovy.lang.Writable#writeTo(java.io.Writer)
     */
    Writer writeTo(Writer outArg) {
        def out
        try {
            out = new BufferedWriter(outArg)
            writeConfig("", this, out, 0, false)
        } finally {
            out.flush()
        }

        return outArg
    }

    private writeConfig(String prefix, ConfigNode node, out, int tab, boolean apply) {
        Closure export = _getConfiguration().valueToCode
        def space = apply ? TAB_CHARACTER * tab : ''
        for (key in node.@map.keySet()) {
            if (key == _getConfiguration().NODE_VALUE_KEY)
                continue
            def value = node.@map.get(key)
            if (value instanceof ConfigValue)
                value = value.value
            if (value instanceof ConfigNode) {
                if (!value.isEmpty()) {
                    if (value.@map.containsKey(_getConfiguration().NODE_VALUE_KEY)) {
                        def val = value.@map[_getConfiguration().NODE_VALUE_KEY]
                        if (val instanceof ConfigValue)
                            val = val.value
                        if (value.@map.size() == 1) { // only value exists
                            writeValue(key, space, prefix, val, out)
                        }
                        if (value.@map.size() > 1) { // children and value exist
                            if (_getConfiguration().resultEnhancementEnabled) {
                                writeValue(key, space, prefix, val, out)
                                writeNode(key, space, tab, value, out)
                            } else {
                                def k = KEYWORDS.contains(key) ? export(key) : key
                                k = "$k(${export(val)})".toString()
                                writeNode(k, space, tab, value, out)
                            }
                        } else { // only children exist
                            writeNode(key, space, tab, value, out)
                        }
                    } else if (value.@map.size() == 1) {
                        def keys = value.@map.keySet() as List
                        def firstKey = keys[0]
                        def firstValue = value.@map[firstKey]
                        if (firstValue instanceof ConfigValue)
                            firstValue = firstValue.value
                        key = KEYWORDS.contains(key) ? export(key) : key
                        def writePrefix = "${prefix}${key}."
                        writeConfig(writePrefix, value, out, tab, true)
                    } else {
                        writeNode(key, space, tab, value, out)
                    }
                }
            } else {
                writeValue(key, space, prefix, value, out)
            }
        }

    }

    protected String _getPath() {
        def current = this
        def path = ''
        while (current.@parent != null) {
            if (path)
                path = ".$path"
            path = "${current.@name}$path"
            current = current.@parent
        }
        return path
    }

    private writeValue(key, space, prefix, value, out) {
        Closure export = _getConfiguration().valueToCode
        key = key.indexOf('.') > -1 ? export(key) : key
        boolean isKeyword = KEYWORDS.contains(key)
        key = isKeyword ? export(key) : key

        if (!prefix && isKeyword) prefix = "this."
        out << "${space}${prefix}$key=${export(value)}"
        out.newLine()
    }

    private writeNode(key, space, tab, value, out) {
        Closure export = _getConfiguration().valueToCode
        key = KEYWORDS.contains(key) ? export(key) : key
        out << "${space}$key {"
        out.newLine()
        writeConfig("", value, out, tab + 1, true)
        def last = "${space}}"
        out << last
        out.newLine()
    }

    void addPropertyChangeListener(PropertyChangeListener listener) {
        pcs.addPropertyChangeListener(listener)
    }

    void addPropertyChangeListener(String property, PropertyChangeListener listener) {
        pcs.addPropertyChangeListener(property, listener)
    }

    void removePropertyChangeListener(PropertyChangeListener listener) {
        pcs.removePropertyChangeListener(listener)
    }

    void removePropertyChangeListener(String property, PropertyChangeListener listener) {
        pcs.removePropertyChangeListener(property, listener)
    }

    boolean hasListeners(String property) {
        pcs.hasListeners(property)
    }

    void getPropertyChangeListeners(String property) {
        pcs.getPropertyChangeListeners(property)
    }

    void getPropertyChangeListeners() {
        pcs.getPropertyChangeListeners()
    }
}

class ConfigValue {
    ConfigNode node
    String name
    def value

    ConfigValue(ConfigNode node, String name, def value, boolean linking = true) {
        this.node = node
        this.name = name
        this.value = value
        if (linking)
            ensureNodeLinks()
    }

    void setValue(def value) {
        this.value = value
        ensureNodeLinks()
    }

    /**
     As soon as a ConfigValue receives a real value, link its node and its parents, so that temporary nodes get persistent
     */
    private ensureNodeLinks() {
        def node = this.node
        def name = this.name
        def value = this.value
        def val = node?.@map?.get(name)
        if (val instanceof ConfigValue)
            val = val.value
        while (val == null) {
            def oldValue = node.@map[name]
            if (oldValue instanceof ConfigValue)
                oldValue = oldValue.value
            node.@map[name] = new ConfigValue(node, name, value, false)
            node._firePropertyChange(name, oldValue, value, true)
            name = node.@name
            value = node
            def oldNode = node
            node = node.@parent
            if (node == null)
                break
            val = node.@map[name]
            if (val instanceof ConfigValue)
                val = val.value
            if (val != null && value.@name == name && !val.is(value) && !this.node.is(value))
                throw new DelayedLinkingConflictException("Conflict when linking ${this.node._getPath()}.${this.name} with value '$value' at key '$name'")
        }
    }
}

@InheritConstructors
class DelayedLinkingConflictException extends Exception {}

interface ConfigNodeProxy {}

interface ConfigFactory {
    /**
     * Creates a new instance each time it is called
     *
     * @param args a List containing the node and the key
     * @return the newly created instance
     * @return the newly created instance
     */
    def create(def args)
}

interface ConfigLazy {
    /**
     * Creates a new instance each time it is called.
     * This will only called the first time and the value will be stored after creation.
     *
     * @param args a List containing the node and the key
     * @return the newly created instance
     */
    def create(def args)
}

class EnhancementMetaClass extends DelegatingMetaClass {
    MetaMethod propertyMissingSet
    MetaMethod propertyMissingGet
    MetaMethod methodMissing
    def key
    ConfigNode node
    def result

    EnhancementMetaClass(def result, def key, ConfigNode node) {
        super(result.metaClass)
        def methods = result.metaClass.methods + result.metaClass.metaMethods
        def pM = methods?.findAll { it?.name == 'propertyMissing'}
        def mM = methods?.findAll { it?.name == 'methodMissing'}

        this.propertyMissingSet = pM?.find { it?.isValidExactMethod(String, Object) }
        this.propertyMissingGet = pM?.find { it?.isValidExactMethod(String) }
        this.methodMissing = mM ? mM[0] : null
        this.key = key
        this.node = node
        this.result = result
    }

    Object invokeMethod(Object object, String methodName, Object[] arguments) {
        if (respondsTo(object, methodName, arguments)) {
            super.invokeMethod(object, methodName, arguments)
        } else {
            if (hasProperty(object, methodName)?.getProperty(object)) {
                super.getProperty(object, methodName).call(arguments)
            } else {
                Object[] args = [methodName] + arguments
                if (respondsTo(object, 'methodMissing', args))
                    super.invokeMethod(object, 'methodMissing', args)
                else if (hasProperty(object, 'methodMissing')?.getProperty(object))
                    super.getProperty(object, 'methodMissing').call(methodName, arguments)
                else
                    node.invokeMethod(methodName, arguments)
            }
        }
    }

    Object getProperty(Object object, String property) {
        if (hasProperty(object, property)?.getProperty(object)) {
            super.getProperty(object, property)
        } else {
            if (respondsTo(object, 'propertyMissing', property)) {
                super.invokeMethod(object, 'propertyMissing', property)
            } else if (hasProperty(object, 'propertyMissing')?.getProperty(object)) {
                def closure = super.getProperty(object, 'propertyMissing')
                closure.call(property)
            } else {
                return node.getProperty(property)
            }
        }
    }

    void setProperty(Object object, String property, Object newValue) {
        if (hasProperty(object, property)?.getProperty(object)) {
            super.setProperty(object, property, newValue)
        } else {
            Object[] args = [property, newValue]
            if (respondsTo(object, 'propertyMissing', args)) {
                super.invokeMethod(object, 'propertyMissing', args)
            } else if (hasProperty(object, 'propertyMissing')?.getProperty(object)) {
                def closure = super.getProperty(object, 'propertyMissing')
                closure.call([property, newValue])
            } else {
                def n = node[key]
                if (n instanceof ConfigNode)
                    n.setProperty(property, newValue)
                else {
                    def newNode = new ConfigNode(key, node, node.@configFile, (ConfigConfiguration) null, node.@parser, node.@script)
                    def oldValue = node.@map[name]
                    if (oldValue instanceof ConfigValue)
                        oldValue = oldValue.value
                    node.@map[key] = new ConfigValue(node, key, newNode)
                    node._firePropertyChange(key, oldValue, newNode)
                    newNode[newNode._getConfiguration().NODE_VALUE_KEY] = n
                    newNode._firePropertyChange(newNode._getConfiguration().NODE_VALUE_KEY, null, n)
                    newNode[property] = newValue
                    newNode._firePropertyChange(property, null, newValue)
                }
            }
        }
    }
}

class CombinedDelegate {
    private def base
    private def sub
    private def binding
    private def resolveStrategy

    CombinedDelegate(def base, def sub, def binding, def resolveStrategy) {
        this.@base = base
        this.@sub = sub
        this.@binding = binding
        this.@resolveStrategy = resolveStrategy
    }

    @Override
    Object getProperty(String property) {
        try {
            def val = this.@binding?.getProperty(property)
            if (val != null)
                return val
        } catch (e) { }
        try {
            def val = this.@sub?.getAt(property)
            if (val != null)
                return val
        } catch (e) { }
        try {
            def val = this.@base?.getAt(property)
            if (val != null)
                return val
        } catch (e) { }
        throw new MissingPropertyException(property, this.@base.getClass())
    }

    @Override
    void setProperty(String property, Object newValue) {
        this.@sub.setProperty(property, newValue)
    }

    @Override
    Object invokeMethod(String name, Object args) {
        try {
            def cls = binding?.getAt(name)
            if (cls instanceof Closure)
                return cls(* args)
        } catch (e) { }

        if(this.@resolveStrategy == Closure.OWNER_ONLY)
            throw new MissingMethodException(this.@base, name, args)

        try {
            return InvokerHelper.invokeMethod(this.@sub, name, args)
        } catch (e) { }
        return InvokerHelper.invokeMethod(this.@base, name, args)
    }
}