package tran;

import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * @author YouDong
 * @date 2023/6/7 23:16
 */
@Service
public class PayServiceImpl {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int pay() {
		// 往test表新增一条数据
		jdbcTemplate.execute("insert into test values (null, 'test')");
		System.out.println("数据库插入成功！");
		// 嵌套事务
		((PayServiceImpl) AopContext.currentProxy()).closeOrder();
//		closeOrder();
		return 1;
	}


	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int closeOrder() {
		// 往test表新增一条数据
		jdbcTemplate.execute("insert into test values (null, 'if')");
		System.out.println("数据库插入成功！");
//		return 0/0;
		return 0;
	}
}