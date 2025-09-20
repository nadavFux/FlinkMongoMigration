package com.example.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

public class Order {
    @JsonProperty("_id")
    private String _id;

    // Default constructor
    public Order() {}

    // Constructor
    public Order(String _id) {
        this._id = _id;
    }

    // Getters and Setters
    public String getOrderId() {
        return _id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Order order = (Order) o;
        return Objects.equals(_id, order._id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(_id);
    }

    @Override
    public String toString() {
        return "Order{" +
                "orderId='" + _id +
                '}';
    }
}