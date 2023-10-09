package tran;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * @author YouDong
 * @date 2023/6/7 23:00
 */
@EnableTransactionManagement
@EnableAspectJAutoProxy(exposeProxy = true, proxyTargetClass = true)
@Configuration
public class TranConfig {

	/**
	 * 配置数据源
	 */
	@Bean
	public DataSource dataSource() throws SQLException {
		SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
		dataSource.setDriver(new com.mysql.cj.jdbc.Driver());
		dataSource.setUrl("jdbc:mysql://localhost:3306/test?useUnicode=true&characterEncoding=utf8&serverTimezone=GMT%2B8&useSSL=false");
		dataSource.setUsername("root");
		dataSource.setPassword("root");
		return dataSource;
	}

	/**
	 * 配置事务管理器
	 */
	@Bean
	public PlatformTransactionManager txManager() throws SQLException {
		return new DataSourceTransactionManager(dataSource());
	}




	/**
	 * 配置事务管理器
	 */
	@Bean
	public JdbcTemplate getJdbcTemplate() throws SQLException {
		return new JdbcTemplate(dataSource());
	}

}
