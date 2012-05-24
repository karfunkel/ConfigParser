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
    protected factories = [:]

    ConfigParser() { }

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
        def thisObj = this
        def node = new ConfigNode('@', location)

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
                return closure(args)
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
        script.binding = cfgBinding

        script.metaClass = mc
        script.run()
        return node
    }

    void setResolveStrategy(int resolveStrategy) {
        if (resolveStrategy == Closure.TO_SELF)
            throw new IllegalArgumentException('ResolveStrategy TO_SELF is not supported');
        this.@resolveStrategy = resolveStrategy
    }
}

