package groovy.util

import org.codehaus.groovy.runtime.InvokerHelper

class ConfigParser {
    static final int THROW_EXCEPTION = 0
    static final int RETURN_NULL = 1

    GroovyClassLoader classLoader = new GroovyClassLoader()
    /** Delegate for methods only */
    def delegate
    def invokeMethodExceptionHandling = THROW_EXCEPTION
    int resolveStrategy = Closure.OWNER_FIRST
    Map<String, Object> binding = [:]

    Map<String, Object> conditionalValues = [:]

    protected ConfigNode currentNode
    protected Script currentScript

    def ConfigConfiguration configuration = new ConfigConfiguration()

    ConfigParser(Map<String, Object> conditionalValues = [:], Map<String, ? extends Collection> conditionalBlocks = [:]) {
        conditionalBlocks.each {k, v ->
            registerConditionalBlock(k, v)
        }
        this.conditionalValues.putAll(conditionalValues)
    }

    /**
     * Parses a ConfigNode instances from an instance of java.util.Properties
     * @param The java.util.Properties instance
     */
    ConfigNode parse(Properties properties) {
        // TODO: reimplement
    }

    /**
     * Parse the given script as a string and return the configuration node
     *
     * @see ConfigParser#parse(groovy.lang.Script)
     */
    ConfigNode parse(String script) {
        return parse(classLoader.parseClass(script))
    }

    /**
     * Create a new instance of the given script class and parse a configuration node from it
     *
     * @see ConfigParser#parse(groovy.lang.Script)
     */
    ConfigNode parse(Class scriptClass) {
        return parse(scriptClass.newInstance())
    }

    /**
     * Parse the given script into a configuration node
     * @param script The script to parse
     * @return A ConfigNode that can be navigating with dot de-referencing syntax to obtain configuration entries
     */
    ConfigNode parse(Script script) {
        return parse(script, null)
    }

    /**
     * Parses a Script represented by the given URL into a ConfigNode
     *
     * @param scriptLocation The location of the script to parse
     * @return The ConfigNode instance
     */
    ConfigNode parse(URL scriptLocation) {
        return parse(classLoader.parseClass(scriptLocation.text).newInstance(), scriptLocation)
    }

    /**
     * Parses the passed groovy.lang.Script instance using the second argument to allow the ConfigNode
     * to retain an reference to the original location other Groovy script
     *
     * @param script The groovy.lang.Script instance
     * @param location The original location of the Script as a URL
     * @return The ConfigNode instance
     */
    ConfigNode parse(Script script, URL location) {
        try {
            def thisObj = this
            def node = new ConfigNode('@', null, location, configuration.clone())
            this.currentNode = node
            this.currentScript = script

            GroovySystem.metaClassRegistry.removeMetaClass(script.getClass())
            def mc = script.getClass().metaClass
            mc.getProperty = { String name ->
                switch (thisObj.resolveStrategy) {
                    case Closure.DELEGATE_FIRST:
                        try {
                            def val = thisObj.delegate?.getAt(name)
                            if (val != null)
                                return val
                        } catch (e) { }
                        try {
                            def val = thisObj[name]
                            if (val != null)
                                return val
                        } catch (e) { }
                        break
                    case Closure.DELEGATE_ONLY:
                        try {
                            def val = thisObj.delegate?.getAt(name)
                            if (val != null)
                                return val
                        } catch (e) {}
                        break
                    case Closure.OWNER_FIRST:
                        try {
                            def val = thisObj[name]
                            if (val != null)
                                return val
                        } catch (e) { }
                        try {
                            def val = thisObj.delegate?.getAt(name)
                            if (val != null)
                                return val
                        } catch (e) { }
                        break
                    case Closure.OWNER_ONLY:
                        try {
                            def val = thisObj[name]
                            if (val != null)
                                return val
                        } catch (e) {}
                        break
                }
                return node[name]
            }

            mc.invokeMethod = { String name, args ->
                switch (name) {
                    case 'run':
                    case 'print':
                    case 'println':
                    case 'printf':
                        MetaMethod mm = mc.getMetaMethod(name, args)
                        return mm.invoke(script, args)
                }
                def closure = this.@binding[name]
                if (closure != null && closure instanceof Closure)
                    return closure(*args)
                try {
                    if (args.length == 1 && args[0] instanceof Closure) {
                        node.invokeMethod(name, args)
                    } else if (args.length == 2 && args[1] instanceof Closure) {
                        node.invokeMethod(name, args)
                    } else {
                        MetaMethod mm = mc.getMetaMethod(name, args)
                        switch (thisObj.resolveStrategy) {
                            case Closure.DELEGATE_FIRST:
                                try {
                                    return InvokerHelper.invokeMethodSafe(this.@delegate, name, args)
                                } catch (MissingMethodException e) {
                                    if (mm) {
                                        return mm.invoke(delegate, args)
                                    } else {
                                        throw new MissingMethodException(name, getClass(), args)
                                    }
                                }
                            case Closure.DELEGATE_ONLY:
                                return InvokerHelper.invokeMethodSafe(this.@delegate, name, args)
                            case Closure.OWNER_ONLY:
                                if (mm) {
                                    return mm.invoke(delegate, args)
                                } else {
                                    throw new MissingMethodException(name, getClass(), args)
                                }
                            default:
                                if (mm) {
                                    return mm.invoke(delegate, args)
                                } else {
                                    return InvokerHelper.invokeMethodSafe(this.@delegate, name, args)
                                }
                        }
                    }
                } catch (e) {
                    switch (invokeMethodExceptionHandling) {
                        case RETURN_NULL:
                            return null
                        case Closure:
                            return invokeMethodExceptionHandling(e)
                        default:
                            throw e
                    }
                }
            }
            def cfgBinding = new ConfigBinding({ String name, value ->
                node[name] = value
            })
            if (this.binding) {
                cfgBinding.getVariables().putAll(this.binding)
            }
            cfgBinding[configuration.CONDITIONAL_VALUES_KEY] = conditionalValues
            script.binding = cfgBinding

            script.metaClass = mc
            script.run()
            return node
        } finally {
            this.currentNode = null
            this.currentScript = null
        }
    }

    void setResolveStrategy(int resolveStrategy) {
        if (resolveStrategy == Closure.TO_SELF)
            throw new IllegalArgumentException('ResolveStrategy TO_SELF is not supported');
        this.@resolveStrategy = resolveStrategy
    }

    void registerConditionalBlock(String key, Collection values) {
        this.binding[key] = { Closure closure ->
            closure.delegate = new ConditionalDelegate(key, values, this)
            closure.resolveStrategy = Closure.DELEGATE_ONLY
            closure()
        }
    }

    void unregisterConditionalBlock(String key) {
        this.binding.remove(key)
    }

    def getConditionalValue(String key) {
        this.conditionalValues[key]
    }

    def setConditionalValue(String key, def value) {
        this.conditionalValues[key] = value
    }

    class ConditionalDelegate {
        def key
        Collection values
        ConfigParser parser

        ConditionalDelegate(String key, Collection values, ConfigParser parser) {
            this.key = key
            this.values = values
            this.parser = parser
        }

        Object getProperty(String name) {
            parser.currentNode.get(name)
        }

        void setProperty(String name, def value) {
            parser.currentNode.put(name, value)
        }

        def invokeMethod(String name, def args) {
            if (values.contains(name)) {
                def currentValue = parser.conditionalValues[key]
                if (args.size() == 1 && args[0] instanceof Closure) {
                    if (name == currentValue)
                        return args[0].call()
                } else if (args.size() == 2 && args[0] instanceof Closure && args[1] instanceof Closure) {
                    if (args[0].call(currentValue))
                        return args[1].call()
                } else {
                    return parser.currentScript.invokeMethod(name, args)
                }
            }
            return parser.currentScript.invokeMethod(name, args)
        }
    }

    // TODO: merge and other ConfigSlurper methods
}

