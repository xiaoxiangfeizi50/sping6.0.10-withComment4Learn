package org.springframework.axpltest.testAop.annotation;

import org.springframework.axpltest.testAop.annotation.config.SpringConfiguration;
import org.springframework.axpltest.testAop.annotation.service.MyCalculator;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class TestAnnotationAop {

    public static void main(String[] args) throws NoSuchMethodException {
		// ---------方式一： 带参的构造，里面有this(), register(), refresh() -----------------
//		AnnotationConfigApplicationContext ac1 = new AnnotationConfigApplicationContext(SpringConfiguration.class);
//		MyCalculator bean1 = ac1.getBean(MyCalculator.class);
//		bean1.add(1,1);
		// ------------------------------------------------------------------------------

		// 方式二： 无参构造，手动调用 register(), refresh()
		AnnotationConfigApplicationContext ac = new AnnotationConfigApplicationContext();
        ac.register(SpringConfiguration.class);
        ac.refresh();
        MyCalculator bean = ac.getBean(MyCalculator.class);
        System.out.println(bean.add(1, 1));
    }
}
