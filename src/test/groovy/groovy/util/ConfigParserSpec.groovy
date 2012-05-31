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
        parser.metaClass.test = 'Owner'
        parser.metaClass.test3 = true
        def exception
        parser.delegate = [test: 'Delegate', test2: true]
        def src = '''
key1 = test
key3 = test2
key4 = test3
'''
        ConfigNode node

        expect:
        parser.resolveStrategy == Closure.OWNER_FIRST

        when:
        node = parser.parse(src)

        then:
        node.key1 == 'Owner'
        node.key3 == true
        node.key4 == true

        when:
        parser.resolveStrategy = Closure.DELEGATE_FIRST
        node = parser.parse(src)

        then:
        node.key1 == 'Delegate'
        node.key3 == true
        node.key4 == true

        when:
        parser.resolveStrategy = Closure.OWNER_ONLY
        node = parser.parse(src)

        then:
        node.key1 == 'Owner'
        node.key3 == null
        node.key4 == true

        when:
        parser.resolveStrategy = Closure.DELEGATE_ONLY
        node = parser.parse(src)

        then:
        node.key1 == 'Delegate'
        node.key3 == true
        node.key4 == null
    }

    def "Test if binding variables work"() {
        setup:
        ConfigParser parser = new ConfigParser()
        parser.binding.test = 'Binding'
        parser.binding.test2 = { 'Binding' }
        parser.metaClass.test = 'Owner'
        parser.metaClass.test2 = { 'Owner' }
        def exception
        parser.delegate = [test: 'Delegate', test2: { 'Delegate' }]
        def src = '''
key1 = test
key2 = test2()
'''
        ConfigNode node

        when:
        node = parser.parse(src)

        then:
        node.key1 == 'Binding'
        node.key2 == 'Binding'

        when:
        parser.resolveStrategy = Closure.DELEGATE_FIRST
        node = parser.parse(src)

        then:
        node.key1 == 'Binding'
        node.key2 == 'Binding'

        when:
        parser.resolveStrategy = Closure.OWNER_ONLY
        node = parser.parse(src)

        then:
        node.key1 == 'Binding'
        node.key2 == 'Binding'

        when:
        parser.resolveStrategy = Closure.DELEGATE_ONLY
        node = parser.parse(src)

        then:
        node.key1 == 'Binding'
        node.key2 == 'Binding'
    }

    def "Test if ConfigFactory works"() {
        setup:
        ConfigParser parser = new ConfigParser()

        when:
        ConfigNode node = parser.parse('''
key1 = { 'Normal' }
key2 = { 'ConfigFactory' } as ConfigFactory
key5({ 'Normal' }) {}
key6.$ = { 'ConfigFactory' } as ConfigFactory
''')
        node.@map.key3 = { 'Normal' }
        node.@map.key4 = { 'ConfigFactory' } as ConfigFactory

        then:
        node.key1 instanceof Closure
        node.key1() == 'Normal'
        node.key2 == 'ConfigFactory'
        node.key3 instanceof Closure
        node.key3() == 'Normal'
        node.key4 == 'ConfigFactory'
        node.key5 instanceof Closure
        node.key5() == 'Normal'
        node.key6 == 'ConfigFactory'
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
        parser.binding.abc = { 'ABC' }

        when:
        ConfigNode node = parser.parse '''
test = 123
environments {
    test {
        test = 456
        abc = abc()
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
        parser.binding.abc = { 'ABC' }

        when:
        ConfigNode node = parser.parse '''
test = 123
mytest {
    test = 456
    abc = abc()
}

env = conditionalValues.mytest
'''
        then:
        node.test == 456
        node.abc == 'ABC'
        node.env == true
    }

    def "Test getRecursive and setRecursive"() {
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
        node.setRecursive('group1.group2.key1', 789)
        node.setRecursive('group1.group2', 'def')
        node.setRecursive('a...b.c', 'def')
        node.setRecursive('x.y.z', 200)

        then:
        node.getRecursive('group1.group2.key1') == 789
        node.getRecursive('group1.group2') == 'def'
        node.getRecursive('a.b.c') == 'def'
        node.x.y == 100
        node.x.y.z == 200
        !(node.x.y.metaClass instanceof EnhancementMetaClass)
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

}

