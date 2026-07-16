cmake_minimum_required(VERSION 3.22.1)
project(apkharden C)

add_library(apkharden SHARED apkharden.c)
set_target_properties(apkharden PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
)

find_library(log-lib log)
find_library(z-lib z)
target_link_libraries(apkharden ${log-lib} ${z-lib})
target_compile_options(apkharden PRIVATE -Oz -fvisibility=hidden -fdata-sections -ffunction-sections)
target_link_options(apkharden PRIVATE
    -Wl,--gc-sections
    -Wl,--strip-all
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)
