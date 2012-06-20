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

import groovy.transform.AutoClone
import org.codehaus.groovy.runtime.InvokerHelper
import org.codehaus.groovy.control.CompilerConfiguration

/**
 * <p>
 * ConfigParser is a utility class for reading configuration files defined in the form of Groovy
 * scripts. Configuration settings can be defined using dot notation or scoped using closures
 *
 * <pre><code>
 *    grails.webflow.stateless = true
 *    smtp {*        mail.host = 'smtp.myisp.com'
 *        mail.auth.user = 'server'
 *}*    proxy = 'http://proxy.mycompany.com:4580'
 *    proxy.user = 'myUser'
 *    proxy.password = 'myPassword'
 *    resources.URL = "http://localhost:80/resources"
 * </pre></code>
 *
 * <p>This is an enhanced,more flexible rewrite of ConfigSlurper
 *
 * TODO: Documentation
 * TODO: Optional cleanup of the code
 * TODO: Enhance writing possibilites
 *
 * @author Alexander Klein
 * @since 2.0
 */
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

    ConfigConfiguration configuration = new ConfigConfiguration()

    ConfigParser(Map<String, Object> conditionalValues = [:], Collection<String> conditionalBlocks) {
        for (def block : conditionalBlocks) {
            registerConditionalBlock(block)
        }
        this.conditionalValues.putAll(conditionalValues)

    }

    ConfigParser(Map<String, Object> conditionalValues = [:], Map<String, ? extends Collection> conditionalBlocks = [:]) {
        conditionalBlocks.each {k, v ->
            if (v == null)
                registerConditionalBlock(k)
            else
                registerConditionalBlock(k, v)
        }
        this.conditionalValues.putAll(conditionalValues)
    }

    /**
     * Parses a ConfigNode instances from an instance of java.util.Properties
     * @param The java.util.Properties instance
     */
    ConfigNode parse(Properties properties) {
        def node = new ConfigNode('@', (ConfigNode) null, (URL) null, configuration.clone(), this, (Script) null)
        def old = node._getConfiguration().resultEnhancementEnabled
        node._getConfiguration.resultEnhancementEnabled = true
        for (key in properties.keySet())
            node.putRecursive(key, properties.getProperty(key))
        node._getConfiguration().resultEnhancementEnabled = old
        return node
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
        def old = configuration.resultEnhancementEnabled
        def node
        try {
            def thisObj = this
            node = new ConfigNode('@', null, location, configuration.clone(), this, script)
            node._getConfiguration().resultEnhancementEnabled = true
            this.currentNode = node
            this.currentScript = script

            GroovySystem.metaClassRegistry.removeMetaClass(script.getClass())
            def mc = script.getClass().metaClass
            mc.getProperty = { String name ->
                if (name == 'binding') {
                    MetaProperty mp = mc.getMetaProperty(name)
                    return mp.getProperty(script)
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
                def closure = thisObj.@binding[name]
                if (closure != null && closure instanceof Closure) {
                    return closure(* args)
                }
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
                                    return InvokerHelper.invokeMethodSafe(thisObj.@delegate, name, args)
                                } catch (MissingMethodException e) {
                                    if (mm) {
                                        return mm.invoke(delegate, args)
                                    } else {
                                        throw new MissingMethodException(name, getClass(), args)
                                    }
                                }
                            case Closure.DELEGATE_ONLY:
                                return InvokerHelper.invokeMethodSafe(thisObj.@delegate, name, args)
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
                                    return InvokerHelper.invokeMethodSafe(thisObj.@delegate, name, args)
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
                binding.put(configuration.CONDITIONAL_VALUES_KEY, conditionalValues)
                cfgBinding.getVariables().putAll(this.binding)
            }
            script.binding = cfgBinding

            script.metaClass = mc
            script.run()
            return node
        } finally {
            node._getConfiguration().resultEnhancementEnabled = old
            this.currentNode = null
            this.currentScript = null
        }
    }

    void setResolveStrategy(int resolveStrategy) {
        if (resolveStrategy == Closure.TO_SELF)
            throw new IllegalArgumentException('ResolveStrategy TO_SELF is not supported');
        this.@resolveStrategy = resolveStrategy
    }

    void registerConditionalBlock(String key, Collection values = null) {
        if (values instanceof Collection) {
            this.binding[key] = { Closure closure ->
                closure.delegate = new ConditionalDelegate(key, values, this)
                closure.resolveStrategy = Closure.DELEGATE_ONLY
                closure()
            }
            this.binding[key].delegate = [conditionalBlock: true, values: values]
        } else {
            this.binding[key] = { Closure closure ->
                if (this.conditionalValues[key]) {
                    closure.delegate = this.delegate
                    closure.resolveStrategy = this.resolveStrategy
                    closure()
                }
            }
            this.binding[key].delegate = [conditionalBlock: true]
        }
    }

    void addOptionsToConditionalBlock(String key, def values) {
        if (values == null) {
            return
        } else if (values instanceof Object[]) {
            values = values.toList()
        } else if (!(values instanceof Collection)) {
            values = [values]
        }
        Closure condition = this.binding[key]
        if (condition == null) {
            registerConditionalBlock(key, values)
            return
        }

        try {
            condition.conditionalBlock
        } catch (MissingPropertyException e) {
            throw new IllegalArgumentException("The closure at key '$key' is not a conditionalBlock")
        }
        if (condition.values instanceof Collection) {
            condition.values.addAll(values)
        } else {
            registerConditionalBlock(key, values)
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
            def currentValue = parser.conditionalValues[key]
            if (values.contains(name)) {
                if (args.size() == 1 && args[0] instanceof Closure) {
                    if (name == currentValue)
                        return args[0].call()
                }
            }
            if (name == parser.configuration.CONDITIONAL_CUSTOM_TEST_KEY) {
                if (args.size() == 2 && args[1] instanceof Closure) {
                    if (args[0].isCase(currentValue))
                        return args[1].call()
                }
            }
            return parser.currentScript.invokeMethod(name, args)
        }
    }
}

@AutoClone
class ConfigConfiguration {
    String CONDITIONAL_VALUES_KEY = 'conditionalValues'
    String CONDITIONAL_CUSTOM_TEST_KEY = 'custom'
    String NODE_VALUE_KEY = '$'
    boolean resultEnhancementEnabled = false
    boolean lazyEvaluationEnabled = true
    boolean factoryEvaluationEnabled = true
    boolean observable = true
}
