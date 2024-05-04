package org.springframework.axpltest.testAop.xml;

import org.springframework.axpltest.testAop.xml.service.MyCalculator;
import org.springframework.cglib.core.DebuggingClassWriter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.lang.reflect.Field;
import java.util.Properties;

public class TestAop {

    public static void main(String[] args) throws Exception {
		f();
//		g();
    }

	public static void f() throws NoSuchMethodException {
		ApplicationContext ac = new ClassPathXmlApplicationContext("testAop.xml");
		MyCalculator bean = ac.getBean(MyCalculator.class);
		System.out.println(bean.toString());
		bean.add(1,1);
	}

	public static void g() throws Exception {
		saveGeneratedCGlibProxyFiles(System.getProperty("user.dir")+"/proxy");
		ApplicationContext ac = new ClassPathXmlApplicationContext("aop.xml");
		MyCalculator bean = ac.getBean(MyCalculator.class);
		System.out.println(bean.toString());
		bean.add(1,1);
		bean.sub(1,1);
	}

    public static void saveGeneratedCGlibProxyFiles(String dir) throws Exception {
        Field field = System.class.getDeclaredField("props");
        field.setAccessible(true);
        Properties props = (Properties) field.get(null);
        System.setProperty(DebuggingClassWriter.DEBUG_LOCATION_PROPERTY, dir);//dir为保存文件路径
        props.put("net.sf.cglib.core.DebuggingClassWriter.traceEnabled", "true");
    }
}
