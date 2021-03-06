package com.fw.test.persitence.entity;

import java.util.List;

import com.yukthi.persistence.ICrudRepository;
import com.yukthi.persistence.repository.annotations.Condition;
import com.yukthi.persistence.repository.annotations.Field;

public interface IOrderRepository extends ICrudRepository<Order>
{
	public List<Order> findOrdersWithItem(@Condition("items.itemName") String itemName);
	
	public List<Order> findOrdersOfCusomer(@Condition("customer.name") String customerName);
	
	public List<Order> findOrdersOfCusomerGroup(@Condition("customer.customerGroups.name") String customerGroupName);

	@Field("customer.name")
	public String findCustomerName(@Condition("orderNo") int orderNo);
	
	public Order findOrderByOrderNo(int orderNo);
}
