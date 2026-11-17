add_executable(fuzz_frame fuzz_frame.cpp)
target_compile_options(fuzz_frame PRIVATE -fsanitize=fuzzer,address,undefined)
target_link_options(fuzz_frame PRIVATE -fsanitize=fuzzer,address,undefined)
target_link_libraries(fuzz_frame PRIVATE wavenet_core)
