cmake_minimum_required(VERSION 3.13)
project(noinstall C)

# A static library that builds successfully but is never installed - there are no install() rules.
add_library(noinstall STATIC noinstall.c)
