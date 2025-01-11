# Tai-e Framework

Tai-e is a state-of-the-art Java program analysis framework developed by the PASCAL Lab at Nanjing University. Using three-address code as its intermediate representation (IR), it captures comprehensive static information from Java programs.

## Core Features

1. **Intermediate Representation (IR)**

   - Three-address code as core IR
   - Simplified representation of Java bytecode instructions
   - Facilitates various program analyses

2. **Analysis Capabilities**

   - Pointer Analysis
   - Call Graph Construction
   - Data Flow Analysis
   - Taint Analysis
   - Concurrency Analysis

3. **Framework Design**
   - Modular architecture
   - Extensible analysis interfaces
   - Rich analysis toolkit
   - Comprehensive testing framework

## Key Components

### 1. IR System

- **Jimple-like IR**: Similar to Soot's Jimple but more streamlined and optimized
- **Type System**: Full support for Java type hierarchy
- **Control Flow Graph**: Automatic construction and maintenance

### 2. Analysis Engine

- **Analysis Scheduler**: Manages execution order of different analyses
- **Result Manager**: Stores and maintains analysis results
- **Configuration System**: Flexible analysis configuration mechanism

### 3. Tool Support

- **Visualization Tools**: For displaying analysis results
- **Performance Profiler**: Monitors analysis performance
- **Debugging Support**: Facilitates development of new analyses

In current category, each branch contains a unique use case with Tai-e framework, and all codes have passed given test in pascal OJ(until January 2025).
