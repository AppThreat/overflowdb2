# Introduction

This project is a fork of the [overflowdb](https://github.com/ShiftLeftSecurity/overflowdb) project with the following modifications:

- There is no overflowing so this is just an API-compatible DB!
- Removed use of slf4j, MDC contexts, and updated to Java 21 release

## Why?

For large projects, we observed that the Heap Monitor used by the upstream project was too taxing. By removing such complexity and letting modern JVM and OS deal with swapping we noticed significant performance improvements (2-3x)

## License

Apache-2.0
