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

import java.beans.PropertyChangeListener
import java.beans.PropertyChangeEvent
import org.codehaus.groovy.control.CompilerConfiguration

class ConfigParserSpec extends spock.lang.Specification {

    def "Parse a config file"() {
        setup:
        ConfigParser parser = new ConfigParser()
        parser.configuration.resultEnhancementEnabled = true

        when:
        ConfigNode node = parser.parse('''
key1 = 'value'
key2 = 123
group.key1 = 'value1'
group {
    key2 = 567
}
role('Blah') {
    key3 = 'Test'
}
test('xxx') {}
copy = role
a.b.c = 'Test2'
a.b = 'Test1'
x.b = 'Test1'
x.b.d = 'Test2'
x.b {
    c = 'Test2'
}
e.f = 'Test1'
e.f('Test') {
    a = 'Test2'
}
y.z = { -> 'Test' }
v.w = null
w = null
f.$ = null
''')

        then:
        node.key1 == 'value'
        node.key2 == 123
        node.group.key1 == 'value1'
        node.group.key2 == 567
        node.test == 'xxx'
        node.role == 'Blah'
        'Blah' != node.role
        'Blah' == node.role.$
        node.role.$ == 'Blah'
        node.role.key3 == 'Test'
        node.role instanceof ConfigNodeProxy
        node.role.equals('Blah')
        node.copy == 'Blah'
        node.role == 'Blah'
        node.a.b == 'Test1'
        node.a.b.c == 'Test2'
        node.x.b == 'Test1'
        node.x.b.d == 'Test2'
        node.x.b.c == 'Test2'
        node.y.z instanceof Closure
        node.y.z.call() == 'Test'
        node.y.z() == 'Test'
        node.v.w == null
        node.w == null
        node.f == null
        node.a.b.blah instanceof ConfigNode
        node.e.f == 'Test'
        node.e.f.a == 'Test2'
    }

    def "New nodes are linked with a set value only"() {
        setup:
        ConfigNode node = new ConfigNode('@')

        when:
        def test = node.getProperty('test')

        then:
        test instanceof ConfigNode
        node.isEmpty()

        when:
        node.test {
            key1 = 'Test'
        }

        then:
        node.test instanceof ConfigNode
        node.test.key1 == 'Test'

        when:
        node.abc.def.key1 = 'Test'

        then:
        node.abc instanceof ConfigNode
        node.abc.def instanceof ConfigNode
        node.abc.def.key1 == 'Test'
    }

    def "Test if delegate with its resolveStrategy works for method calls"() {
        setup:
        ConfigParser parser = new ConfigParser()
        def exception
        parser.invokeMethodExceptionHandling = { exception = it; return null }
        parser.delegate = [test: { "Delegate${it ?: ''}" }, test2: { true }]
        def src = '''
def test(def it = null) { "Owner${it ?: ''}" }
def test3() { true }

key1 = test()
key2 = test('Test')
key3 = test2()
key4 = test3()
'''
        ConfigNode node

        expect:
        parser.resolveStrategy == Closure.OWNER_FIRST

        when:
        node = parser.parse(src)

        then:
        node.key1 == 'Owner'
        node.key2 == 'OwnerTest'
        node.key3 == true
        node.key4 == true

        when:
        parser.resolveStrategy = Closure.DELEGATE_FIRST
        node = parser.parse(src)

        then:
        node.key1 == 'Delegate'
        node.key2 == 'DelegateTest'
        node.key3 == true
        node.key4 == true

        when:
        parser.resolveStrategy = Closure.OWNER_ONLY
        node = parser.parse(src)

        then:
        node.key1 == 'Owner'
        node.key2 == 'OwnerTest'
        node.key3 == null
        node.key4 == true

        when:
        parser.resolveStrategy = Closure.DELEGATE_ONLY
        node = parser.parse(src)

        then:
        node.key1 == 'Delegate'
        node.key2 == 'DelegateTest'
        node.key3 == true
        node.key4 == null
        exception instanceof MissingMethodException
        exception.method == 'test3'

        when:
        parser.resolveStrategy = Closure.TO_SELF

        then:
        thrown(IllegalArgumentException)

    }

    def "Test if delegate with its resolveStrategy works for properties"() {
        setup:
        ConfigParser parser = new ConfigParser()
        CompilerConfiguration cfg = new CompilerConfiguration()
        cfg.setScriptBaseClass(TestBaseClass.name)
        parser.classLoader = new GroovyClassLoader(Thread.currentThread().getContextClassLoader(), cfg)
        def exception
        parser.delegate = [test: 'Delegate', test2: 'Delegate', test5: { 'Delegate' }]
        def src = '''
key1 = test
key2 = test2
key3 = test3
try { key4 = test4() } catch(e) { key4 = null }
try { key5 = test5() } catch(e) { key5 = null }
try { key6 = test6() } catch(e) { key6 = null }
group {
    key1 = test
    key2 = test2
    key3 = test3
    try { key4 = test4() } catch(e) { key4 = null }
    try { key5 = test5() } catch(e) { key5 = null }
    try { key6 = test6() } catch(e) { key6 = null }
}
'''
        ConfigNode node

        expect:
        parser.resolveStrategy == Closure.OWNER_FIRST

        when:
        node = parser.parse(src)

        then:
        node.key1 == 'Owner'
        node.key2 == 'Delegate'
        node.key3 == 'Owner'
        node.key4 == 'Owner'
        node.key5 == 'Delegate'
        node.key6 == 'Owner'
        node.group.key1 == 'Owner'
        node.group.key2 == 'Delegate'
        node.group.key3 == 'Owner'
        node.group.key4 == 'Owner'
        node.group.key5 == 'Delegate'
        node.group.key6 == 'Owner'

        when:
        parser.resolveStrategy = Closure.DELEGATE_FIRST
        node = parser.parse(src)

        then:
        node.key1 == 'Delegate'
        node.key2 == 'Delegate'
        node.key3 == 'Owner'
        node.key4 == 'Owner'
        node.key5 == 'Delegate'
        node.key6 == 'Owner'
        node.group.key1 == 'Delegate'
        node.group.key2 == 'Delegate'
        node.group.key3 == 'Owner'
        node.group.key4 == 'Owner'
        node.group.key5 == 'Delegate'
        node.group.key6 == 'Owner'

        when:
        parser.resolveStrategy = Closure.OWNER_ONLY
        node = parser.parse(src)

        then:
        node.key1 == 'Owner'
        node.key2 == null
        node.key3 == 'Owner'
        node.key4 == 'Owner'
        node.key5 == null
        node.key6 == 'Owner'
        node.group.key1 == 'Owner'
        node.group.key2 == null
        node.group.key3 == 'Owner'
        node.group.key4 == 'Owner'
        node.group.key5 == null
        node.group.key6 == 'Owner'

        when:
        parser.resolveStrategy = Closure.DELEGATE_ONLY
        node = parser.parse(src)

        then:
        node.key1 == 'Delegate'
        node.key2 == 'Delegate'
        node.key3 == null
        node.key4 == null
        node.key5 == 'Delegate'
        node.key6 == null
        node.group.key1 == 'Delegate'
        node.group.key2 == 'Delegate'
        node.group.key3 == null
        node.group.key4 == null
        node.group.key5 == 'Delegate'
        node.group.key6 == null
    }

    def "Test if binding variables work"() {
        setup:
        ConfigParser parser = new ConfigParser()
        parser.binding['test'] = 'Binding'
        parser.binding.test2 = { 'Binding' }
        parser.metaClass.test = 'Owner'
        parser.metaClass.test2 = { 'Owner' }
        def exception
        parser.delegate = [test: 'Delegate', test2: { 'Delegate' }]
        def src = '''
key1 = test
key2 = test2()
group {
    key1 = test
    key2 = test2()
}
'''
        ConfigNode node

        when:
        node = parser.parse(src)

        then:
        node.key1 == 'Binding'
        node.key2 == 'Binding'
        node.group.key1 == 'Binding'
        node.group.key2 == 'Binding'

        when:
        parser.resolveStrategy = Closure.DELEGATE_FIRST
        node = parser.parse(src)

        then:
        node.key1 == 'Binding'
        node.key2 == 'Binding'
        node.group.key1 == 'Binding'
        node.group.key2 == 'Binding'

        when:
        parser.resolveStrategy = Closure.OWNER_ONLY
        node = parser.parse(src)

        then:
        node.key1 == 'Binding'
        node.key2 == 'Binding'
        node.group.key1 == 'Binding'
        node.group.key2 == 'Binding'

        when:
        parser.resolveStrategy = Closure.DELEGATE_ONLY
        node = parser.parse(src)

        then:
        node.key1 == 'Binding'
        node.key2 == 'Binding'
        node.group.key1 == 'Binding'
        node.group.key2 == 'Binding'
    }

    def "Test if ConfigFactory works"() {
        setup:
        ConfigParser parser = new ConfigParser()

        when:
        ConfigNode node = parser.parse('''
key1 = { 'Normal' }
key2 = { "ConfigFactory ${new Date().time}" } as ConfigFactory
key5({ 'Normal' }) {}
key6.$ = { "ConfigFactory ${new Date().time}" } as ConfigFactory
''')
        node.@map.key3 = { 'Normal' }
        node.@map.key4 = { "ConfigFactory ${new Date().time}" } as ConfigFactory

        then:
        node.key1 instanceof Closure
        node.key1() == 'Normal'
        checkFactory(node, 'key2')
        node.key3 instanceof Closure
        node.key3() == 'Normal'
        checkFactory(node, 'key4')
        node.key5 instanceof Closure
        node.key5() == 'Normal'
        checkFactory(node, 'key6')
    }

    private checkFactory(def node, def property) {
        def first = node[property]
        first.startsWith('ConfigFactory')
        sleep 100
        def second = node[property]
        second.startsWith('ConfigFactory')
        second != first
    }

    def "Test if ConfigLazy works"() {
        setup:
        ConfigParser parser = new ConfigParser()

        when:
        ConfigNode node = parser.parse('''
key1 = { 'Normal' }
key2 = { 'ConfigLazy' } as ConfigLazy
key5({ 'Normal' }) {}
key6.$ = { 'ConfigLazy' } as ConfigLazy
''')
        node.@map.key3 = { 'Normal' }
        node.@map.key4 = { 'ConfigLazy' } as ConfigLazy

        then:
        node.key1 instanceof Closure
        node.key1() == 'Normal'
        def key2 = node.key2
        key2 == 'ConfigLazy'
        key2.is(node.key2)
        node.key3 instanceof Closure
        node.key3() == 'Normal'
        def key4 = node.key4
        key4 == 'ConfigLazy'
        key4.is(node.key4)
        node.key5 instanceof Closure
        node.key5() == 'Normal'
        def key6 = node.key6
        key6 == 'ConfigLazy'
        key6.is(node.key6)
    }

    def "Test Conditional Blocks"() {
        setup:
        ConfigParser parser = new ConfigParser([environments: ['test', 'dev', 'prod']], environments: 'test')
        parser.binding.method = { 'ABC' }

        when:
        ConfigNode node = parser.parse '''
test = 123
environments {
    test {
        test = 456
        abc = method()
    }
    dev {
        test = 789
    }
}
xxx = 123
environments {
    test {
        yyy = 456
    }
    dev {
        yyy = 789
    }
}

environments {
    custom({ it == 'test' }) {
        zzz = 123
    }
    custom(~/t.*t/) {
        xyz = 123
    }
}

env = conditionalValues.environments
'''
        then:
        node.test == 456
        node.abc == 'ABC'
        node.xxx == 123
        node.yyy == 456
        node.zzz == 123
        node.xyz == 123
        node.env == 'test'
    }

    def "Test simple Conditional Blocks"() {
        setup:
        ConfigParser parser = new ConfigParser(['mytest'], mytest: true)
        parser.binding.method = { 'ABC' }

        when:
        ConfigNode node = parser.parse '''
test = 123
mytest {
    test = 456
    abc = method()
}

env = conditionalValues.mytest
'''
        then:
        node.test == 456
        node.abc == 'ABC'
        node.env == true
    }

    def "Test adding conditionalBlockOptions"() {
        setup:
        ConfigParser parser = new ConfigParser()
        parser.registerConditionalBlock('simple')
        parser.setConditionalValue('simple', true)

        when:
        parser.addOptionsToConditionalBlock('extending', ['test1'])
        parser.setConditionalValue('extending', 'test1')
        ConfigNode node = parser.parse '''
extending {
    test1 {
        value = 100
    }
}
'''
        then:
        node.value == 100

        when:
        parser.addOptionsToConditionalBlock('extending', 'test2')
        parser.setConditionalValue('extending', 'test2')
        node = parser.parse '''
extending {
    test2 {
        value = 200
    }
}
'''
        then:
        node.value == 200

        when:
        parser = new ConfigParser()
        parser.binding.extending = { closure -> closure.call() }
        parser.addOptionsToConditionalBlock('extending', 'test1')

        then:
        thrown(IllegalArgumentException)

    }

    def "Test getRecursive, putRecursive and removeRecursive"() {
        setup:
        ConfigParser parser = new ConfigParser()

        when:
        ConfigNode node = parser.parse '''
group1 {
    group2('abc') {
        key1 = 123
        key2 = 456
    }
}
x.y = 100
'''
        then:
        node.getRecursive('group1.group2.key1') == 123
        node.getRecursive('group1.group2.key2') == 456
        node.getRecursive('group1..group2...key2') == 456
        node.getRecursive('group1.group2') == 'abc'

        when:
        node.putRecursive('group1.group2.key1', 789)
        node.putRecursive('group1.group2', 'def')
        node.putRecursive('a...b.c', 'def')
        node.putRecursive('x.y.z', 200)

        then:
        node.getRecursive('group1.group2.key1') == 789
        node.getRecursive('group1.group2') == 'def'
        node.getRecursive('a.b.c') == 'def'
        node.x.y == 100
        node.x.y.z == 200
        !(node.x.y.metaClass instanceof EnhancementMetaClass)

        node.getRecursive('a.d') == null

        when:
        node.removeRecursive('a.b.c')

        then:
        node.getRecursive('a.b.c') == null
    }

    def "Test ConfigNode from Properties"() {
        setup:
        ConfigParser parser = new ConfigParser()
        Properties props = new Properties()
        props.load(new StringReader('''
a = 10
a.b = 20
a.b.c = 30
'''))
        when:
        ConfigNode node = parser.parse(props)

        then:
        node.a == '10'
        node.a.b == '20'
        node.a.b.c == '30'
    }

    def "Test flatten"() {
        setup:
        ConfigParser parser = new ConfigParser()

        when:
        ConfigNode node = parser.parse '''
a = 10
a {
    b {
        c = 30
        d = 40
    }
}
e = 50
'''
        Map flattened = node.flatten()

        then:
        node.a == 10
        node.a.b.c == 30
        node.a.b.d == 40
        node.e == 50

        flattened['a'] == 10
        flattened['a.b.c'] == 30
        flattened['a.b.d'] == 40
        flattened['e'] == 50
        flattened.'a.b.d' == 40

        flattened == [a: 10, 'a.b.c': 30, 'a.b.d': 40, e: 50]
    }

    def "Test toProperties"() {
        setup:
        ConfigParser parser = new ConfigParser()

        when:
        ConfigNode node = parser.parse '''
a = 10
a {
    b {
        c = 30
        d = 40
    }
}
e = 50
'''
        def props = node.toProperties()

        then:
        node.a == 10
        node.a.b.c == 30
        node.a.b.d == 40
        node.e == 50

        props['a'] == '10'
        props['a.b.c'] == '30'
        props['a.b.d'] == '40'
        props['e'] == '50'

        props == [a: '10', 'a.b.c': '30', 'a.b.d': '40', e: '50']
        props instanceof Properties
    }

    def "Test merge"() {
        setup:
        ConfigParser parser = new ConfigParser()
        parser.configuration.resultEnhancementEnabled = true

        when:
        ConfigNode node1 = parser.parse '''
a = 10
a {
    b {
        c = 30
        d = 40
    }
}
e = 50
'''
        ConfigNode node2 = parser.parse '''
a = 20
a {
    b = 20
    b.c = 100
    c = 30
}
f = 50
'''
        ConfigNode node = node1.merge(node2)

        then:
        node.a == 20
        node.a.b.c == 100
        node.a.b.d == 40
        node.e == 50
        node.a.b == 20
        node.a.c == 30
        node.f == 50
    }

    def "Test writeTo"() {
        setup:
        ConfigParser parser = new ConfigParser()
        parser.configuration.resultEnhancementEnabled = false

        when:
        ConfigNode node = parser.parse '''
a = '10'
a {
    b {
        c = '30'
        d = '40'
    }
    f.g = '100'
    h.i.j {
        k = 12.345
    }
    l.m.n {
        o = 20
        p = '20'
    }

}
e = 5.0
'''
        StringWriter sw = new StringWriter()
        node.writeTo(sw)

        then:
        sw.toString() == '''a('10') {
\tb {
\t\tc='30'
\t\td='40'
\t}
\tf.g='100'
\th.i.j.k=12.345
\tn {
\t\to=20
\t\tp='20'
\t}
}
e=5.0
'''
        when:
        parser.configuration.resultEnhancementEnabled = true
        node = parser.parse '''
a = '10'
a {
    b {
        c = '30'
        d = '40'
    }
    f.g = '100'
    h.i.j {
        k = 12.345
    }
    l.m.n {
        o = 20
        p = '20'
    }

}
e = 5.0
'''
        sw = new StringWriter()
        node.writeTo(sw)

        then:
        sw.toString() == '''a='10'
a {
\tb {
\t\tc='30'
\t\td='40'
\t}
\tf.g='100'
\th.i.j.k=12.345
\tn {
\t\to=20
\t\tp='20'
\t}
}
e=5.0
'''

    }

    def "Delayed linking has to be resolved"() {
        setup:
        ConfigNode node = new ConfigNode('@')

        when:
        def test = node.test.a.b

        then:
        test instanceof ConfigNode
        node.isEmpty()

        when:
        node.test.a.b.c = 200

        then:
        node.test.a.b.c == 200

        when:
        test.c = 100

        then:
        thrown(DelayedLinkingConflictException)
    }

    def "Test observable"() {
        setup:
        ConfigParser parser = new ConfigParser()
        parser.configuration.resultEnhancementEnabled = true

        ConfigNode node = parser.parse '''
a {
    b = 1
    c = 2
    c {
        d = 3
    }
}
e = 4
'''
        def events = []
        node.addPropertyChangeListener({ PropertyChangeEvent evt -> events << evt } as PropertyChangeListener)

        when:
        node.e = 5

        then:
        events.size() == 1
        events[0].propertyName == 'e'
        events[0].oldValue == 4
        events[0].newValue == 5

        when:
        events.clear()
        node.f = 5

        then:
        events.size() == 1
        events[0].propertyName == 'f'
        events[0].oldValue == null
        events[0].newValue == 5

        when:
        events.clear()
        node.g(6) {}

        then:
        events.size() == 2
        events[0].propertyName == 'g.$'
        events[0].oldValue == null
        events[0].newValue == 6
        events[1].propertyName == 'g'
        events[1].oldValue == null
        events[1].newValue.size() == 1
        events[1].newValue.$ == 6

        when:
        events.clear()
        node.a.b = 7

        then:
        events.size() == 1
        events[0].propertyName == 'a.b'
        events[0].oldValue == 1
        events[0].newValue == 7

        when:
        events.clear()
        node.a = 8

        then:
        events.size() == 1
        events[0].propertyName == 'a.$'
        events[0].oldValue == null
        events[0].newValue == 8

        when:
        events.clear()
        node.a.c = 9

        then:
        events.size() == 1
        events[0].propertyName == 'a.c.$'
        events[0].oldValue == 2
        events[0].newValue == 9

        when:
        events.clear()
        node.a.h { i = 10 }

        then:
        events.size() == 2
        events[0].propertyName == 'a.h'
        events[0].oldValue == null
        events[0].newValue.size() == 1
        events[0].newValue.i == 10
        events[1].propertyName == 'a.h.i'
        events[1].oldValue == null
        events[1].newValue == 10

        when:
        events.clear()
        PropertyChangeListener listener = new PropertyChangeListener() {
            void propertyChange(PropertyChangeEvent evt) {
                events << evt
            }
        }
        node.a.addPropertyChangeListener({ PropertyChangeEvent evt -> events << evt } as PropertyChangeListener)
        node.a.c.addPropertyChangeListener({ PropertyChangeEvent evt -> events << evt } as PropertyChangeListener)
        node.a.c.d = 100

        then:
        events.size() == 3
        events[0].propertyName == 'd'
        events[0].oldValue == 3
        events[0].newValue == 100
        events[1].propertyName == 'c.d'
        events[1].oldValue == 3
        events[1].newValue == 100
        events[2].propertyName == 'a.c.d'
        events[2].oldValue == 3
        events[2].newValue == 100
    }
}

abstract class TestBaseClass extends Script {
    def test = 'Owner'
    def test3 = 'Owner'
    def test4() {
        'Owner'
    }
    def test6() {
        'Owner'
    }
}


