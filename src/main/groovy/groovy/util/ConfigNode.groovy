package groovy.util

class ConfigNode {
    static final String NODE_VALUE_KEY = '$'

    @Delegate
    protected Map map = [:] as LinkedHashMap

    protected Map classMap = [:] as LinkedHashMap
    protected GroovyShell shell = new GroovyShell(ConfigNode.classLoader)

    protected URL configFile
    protected ConfigNode parent
    protected String name

    ConfigNode(String name, ConfigNode parentNode = null, URL file = null) {
        this.@name = name
        parent = parentNode
        configFile = file
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
                def cfgValue = value.@map.get(NODE_VALUE_KEY) // value for nodes with children AND a value
                if (cfgValue != null) {
                    // if we have a nodes with children AND a value
                    def val = cfgValue instanceof ConfigValue ? cfgValue.value : cfgValue // use inner value
                    if (value.@map.size() == 1)
                        return prepareResult(value, NODE_VALUE_KEY, val) // if the node only has a value, but no children, return the value only
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
    Object methodMissing(String name, Object args) { println name+args; return map.invokeMethod(name, args) }
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
        if (value instanceof ConfigFactory) {
            return value.create([this, key])
        } else if (value instanceof ConfigLazy) {
            def val = value.create([this, key])
            setProperty(key, val)
            return val
        } else
            return value
    }

    void setProperty(String name, Object value) {
        put(name, value)
    }

    Object put(Object key, Object value) {
        if (value instanceof ConfigNodeProxy) // do not nest ConfigNodeProxy instances, only use its value
            value = value.@map[NODE_VALUE_KEY]
        def old = map.get(key)
        def oldValue = old instanceof ConfigValue ? old?.value : old
        if (old == null || ! (old instanceof ConfigValue)) // if the value to store is new, store it
            map.put(key, new ConfigValue(this, key, value))
        else if (oldValue instanceof ConfigNode && !(value instanceof ConfigNode)) { // if an existing node should get a value itself
            oldValue = oldValue.@map[NODE_VALUE_KEY] = new ConfigValue(oldValue, key, value) // store the new value at the special property
            if (oldValue instanceof ConfigValue)
                oldValue = oldValue.value
        }
        else
            old.value = value // if the node already exists, reset its value
        return oldValue
    }

    boolean containsValue(Object value) {
        if (value instanceof ConfigNodeProxy) // use the value of ConfigNodeProxy instances
            value = value.@map[NODE_VALUE_KEY]
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
            get(name).put(NODE_VALUE_KEY, args[0])
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
                node.@map[NODE_VALUE_KEY] = new ConfigValue(node, NODE_VALUE_KEY, val)
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



