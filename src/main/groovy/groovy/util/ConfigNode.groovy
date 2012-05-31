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

    ConfigNode(String name, ConfigNode parentNode = null, URL file = null, ConfigConfiguration configuration = null) {
        this.@name = name
        this.@configuration = configuration
        parent = parentNode
        configFile = file
    }

    protected ConfigConfiguration _getConfiguration() {
        if (!configuration) {
            try {
                if (parent instanceof ConfigNode)
                    return parent._getConfiguration()
                else
                    return parent.configuration ?: new ConfigConfiguration()
            } catch (e) {
                return new ConfigConfiguration()
            }
        }
        return configuration
    }

    def getProperty(String name) {
        return get(name)
    }

    Object get(Object key) {
        boolean newNode = false
        def value
        if (!map.containsKey(key)) {
            // create a new, lazy ConfigNode to enable subproperties, but don't put it into the map
            value = new ConfigNode(key, this, configFile)
            newNode = true
        }
        value = value == null ? map.get(key) : value
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
                    if (value.@map.size() == 1)
                        return prepareResult(value, _getConfiguration().NODE_VALUE_KEY, val) // if the node only has a value, but no children, return the value only
                    else {
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
            if (part)
                current = current.getProperty(part)
        }
        return current
    }

    void setProperty(String name, Object value) {
        put(name, value)
    }

    Object put(Object key, Object value) {
        if (value instanceof ConfigNodeProxy) // do not nest ConfigNodeProxy instances, only use its value
            value = value.@map[_getConfiguration().NODE_VALUE_KEY]
        def old = map.get(key)
        def oldValue = old instanceof ConfigValue ? old?.value : old
        if (old == null || !(old instanceof ConfigValue)) // if the value to store is new, store it
            map.put(key, new ConfigValue(this, key, value))
        else if (oldValue instanceof ConfigNode && !(value instanceof ConfigNode)) { // if an existing node should get a value itself
            oldValue = oldValue.@map[_getConfiguration().NODE_VALUE_KEY] = new ConfigValue(oldValue, _getConfiguration().NODE_VALUE_KEY, value) // store the new value at the special property
            if (oldValue instanceof ConfigValue)
                oldValue = oldValue.value
        }
        else
            old.value = value // if the node already exists, reset its value
        return oldValue
    }

    void setRecursive(String key, def value) {
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

    boolean containsValue(Object value) {
        if (value instanceof ConfigNodeProxy) // use the value of ConfigNodeProxy instances
            value = value.@map[_getConfiguration().NODE_VALUE_KEY]
        return map.containsValue((value instanceof ConfigValue) ? value.value : value)
    }

    Object remove(Object key) {
        def oldConfigNodeProxy = map.remove(key)
        (old instanceof ConfigValue) ? old.value : old
    }

    Collection<Object> values() {
        map.values().collect { (it instanceof ConfigValue) ? it.value : it }
    }

    Set<Map.Entry<Object, Object>> entrySet() {
        map.entrySet().collect {
            (it.value instanceof ConfigValue) ? new Entry(key: it.key, value: it.value.value) : new Entry(key: it.key, value: it.value)
        } as Set<Map.Entry<Object, Object>>
    }

    @Override
    Object invokeMethod(String name, Object args) {
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
        } else
            return super.invokeMethod(name, args)
    }

    private callClosure(String name, Closure closure) {
        def value = map.get(name)
        def val = (value instanceof ConfigValue) ? value.value : value
        if (!(val instanceof ConfigNode)) {
            def node = new ConfigNode(name, this, this.@configFile)
            if (val != null)
                node.@map[_getConfiguration().NODE_VALUE_KEY] = new ConfigValue(node, _getConfiguration().NODE_VALUE_KEY, val)
            value = new ConfigValue(this, name, node)
            map.put(name, value)
        }
        closure.delegate = value.value
        closure.resolveStrategy = Closure.DELEGATE_FIRST
        closure.call()
    }

    class Entry implements Map.Entry<Object, Object> {
        protected Object key
        protected Object value

        Object getKey() { key }

        Object setValue(Object value) {
            def old = this.value
            this.value = value
            return old
        }

        Object getValue() { value }
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
            target = new ConfigNode('@', null, this.@configFile, _getConfiguration().clone())
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
        return merge(this, other)
    }

    private merge(ConfigNode target, ConfigNode other) {
        for (def entry : other.@map) {
            if (entry.value instanceof ConfigValue)
                entry.value = entry.value.value
            def configEntry = target.@map[entry.key]
            if (configEntry instanceof ConfigValue)
                configEntry = configEntry.value
            if (configEntry == null) {
                target.@map[entry.key] = new ConfigValue(target, entry.key, entry.value instanceof ConfigValue ? entry.value.value : entry.value, false)
            } else {
                if (configEntry instanceof ConfigNode) {
                    if (entry.value instanceof ConfigNode) {
                        merge(configEntry, entry.value)
                    } else {
                        configEntry[_getConfiguration().NODE_VALUE_KEY] = new ConfigValue(configEntry, _getConfiguration().NODE_VALUE_KEY, entry.value instanceof ConfigValue ? entry.value.value : entry.value, false)
                    }
                }
                else {
                    target.@map[entry.key] = new ConfigValue(target, entry.key, entry.value instanceof ConfigValue ? entry.value.value : entry.value, false)
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
                                def k = KEYWORDS.contains(key) ? key.inspect() : key
                                k = "$k(${val.inspect()})".toString()
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
                        key = KEYWORDS.contains(key) ? key.inspect() : key
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

    private writeConfig_old(String prefix, ConfigNode node, out, int tab, boolean apply) {
        def space = apply ? TAB_CHARACTER * tab : ''
        for (key in node.@map.keySet()) {
            def value = node.@map.get(key)
            if (value instanceof ConfigValue)
                value = value.value
            if (value instanceof ConfigNode) {
                if (!value.isEmpty()) {
                    def dotsInKeys = value.find { entry -> entry.key.indexOf('.') > -1 }
                    def configSize = value.size()
                    def firstKey = value.keySet().iterator().next()
                    def firstValue = value.values().iterator().next()
                    def firstSize
                    if (firstValue instanceof ConfigNode) {
                        firstSize = firstValue.size()
                    }
                    else { firstSize = 1 }
                    if (configSize == 1 || dotsInKeys) {

                        if (firstSize == 1 && firstValue instanceof ConfigNode) {
                            key = KEYWORDS.contains(key) ? key.inspect() : key
                            def writePrefix = "${prefix}${key}.${firstKey}."
                            writeConfig_old(writePrefix, firstValue, out, tab, true)
                        }
                        else if (!dotsInKeys && firstValue instanceof ConfigNode) {
                            writeNode(key, space, tab, value, out)
                        } else {
                            for (j in value.keySet()) {
                                def v2 = value.get(j)
                                def k2 = j.indexOf('.') > -1 ? j.inspect() : j
                                if (v2 instanceof ConfigNode) {
                                    key = KEYWORDS.contains(key) ? key.inspect() : key
                                    writeConfig_old("${prefix}${key}", v2, out, tab, false)
                                }
                                else {
                                    writeValue("${key}.${k2}", space, prefix, v2, out)
                                }
                            }
                        }

                    }
                    else {
                        writeNode(key, space, tab, value, out)
                    }
                }
            }
            else {
                writeValue(key, space, prefix, value, out)
            }
        }
    }

    private writeValue(key, space, prefix, value, out) {
        key = key.indexOf('.') > -1 ? key.inspect() : key
        boolean isKeyword = KEYWORDS.contains(key)
        key = isKeyword ? key.inspect() : key

        if (!prefix && isKeyword) prefix = "this."
        out << "${space}${prefix}$key=${value.inspect()}"
        out.newLine()
    }

    private writeNode(key, space, tab, value, out) {
        key = KEYWORDS.contains(key) ? key.inspect() : key
        out << "${space}$key {"
        out.newLine()
        writeConfig("", value, out, tab + 1, true)
        def last = "${space}}"
        out << last
        out.newLine()
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
        def val = node.@map.get(name)
        while (val == null) {
            node.@map[name] = new ConfigValue(node, name, value, false)
            name = node.@name
            value = node
            node = node.@parent
            if (node == null)
                break
            val = node.@map.get(name)
        }
    }
}

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
                    def newNode = new ConfigNode(key, node, node.@configFile)
                    node.@map[key] = new ConfigValue(node, key, newNode)
                    newNode[newNode._getConfiguration().NODE_VALUE_KEY] = n
                    newNode[property] = newValue
                }
            }
        }
    }
}



