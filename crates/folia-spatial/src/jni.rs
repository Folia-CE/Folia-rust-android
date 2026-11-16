//! The handful of JNI calls this library makes, declared by hand (as in the rain library).
//!
//! `JNIEnv*` points at a pointer to the JNI function table; the slots used here are fixed by the
//! JNI specification (checked against the NDK's jni.h): GetArrayLength 171,
//! GetPrimitiveArrayCritical 222, ReleasePrimitiveArrayCritical 223, GetDirectBufferAddress 230,
//! GetDirectBufferCapacity 231.

use std::ffi::c_void;
use std::marker::PhantomData;

pub type Env = *mut *const *const c_void;
pub type JObject = *mut c_void;

const GET_ARRAY_LENGTH: usize = 171;
const GET_PRIMITIVE_ARRAY_CRITICAL: usize = 222;
const RELEASE_PRIMITIVE_ARRAY_CRITICAL: usize = 223;
const GET_DIRECT_BUFFER_ADDRESS: usize = 230;
const GET_DIRECT_BUFFER_CAPACITY: usize = 231;

type GetArrayLengthFn = unsafe extern "system" fn(Env, JObject) -> i32;
type GetCriticalFn = unsafe extern "system" fn(Env, JObject, *mut u8) -> *mut c_void;
type ReleaseCriticalFn = unsafe extern "system" fn(Env, JObject, *mut c_void, i32);
type GetDirectAddressFn = unsafe extern "system" fn(Env, JObject) -> *mut c_void;
type GetDirectCapacityFn = unsafe extern "system" fn(Env, JObject) -> i64;

unsafe fn function(env: Env, slot: usize) -> *const c_void {
    *(*env).add(slot)
}

/// The length of a Java array (0 for null). Call before taking any array critically: no other
/// JNI call is allowed while one is held.
pub unsafe fn array_length(env: Env, array: JObject) -> usize {
    if array.is_null() {
        return 0;
    }
    let f: GetArrayLengthFn = std::mem::transmute(function(env, GET_ARRAY_LENGTH));
    f(env, array).max(0) as usize
}

/// A direct ByteBuffer's memory from byte `offset` on, if at least `bytes` are there.
/// Take every buffer before holding any array critically.
pub unsafe fn direct_range(env: Env, buffer: JObject, offset: i32, bytes: usize) -> Option<*mut u8> {
    if buffer.is_null() || offset < 0 {
        return None;
    }
    let address: GetDirectAddressFn = std::mem::transmute(function(env, GET_DIRECT_BUFFER_ADDRESS));
    let capacity: GetDirectCapacityFn = std::mem::transmute(function(env, GET_DIRECT_BUFFER_CAPACITY));
    let base = address(env, buffer) as *mut u8;
    let cap = capacity(env, buffer);
    if base.is_null() || cap < 0 || offset as i64 + bytes as i64 > cap {
        return None;
    }
    Some(base.add(offset as usize))
}

/// A Java primitive array's elements, held without copying until dropped. Hold it briefly: the
/// garbage collector may wait for it.
pub struct Critical<'a, T> {
    env: Env,
    array: JObject,
    elements: *mut T,
    len: usize,
    _lifetime: PhantomData<&'a mut [T]>,
}

impl<'a, T> Critical<'a, T> {
    /// `len` from `array_length`, taken before any array was held.
    pub unsafe fn get(env: Env, array: JObject, len: usize) -> Option<Critical<'a, T>> {
        if array.is_null() {
            return None;
        }
        let f: GetCriticalFn = std::mem::transmute(function(env, GET_PRIMITIVE_ARRAY_CRITICAL));
        let elements = f(env, array, std::ptr::null_mut()) as *mut T;
        if elements.is_null() {
            return None;
        }
        Some(Critical { env, array, elements, len, _lifetime: PhantomData })
    }

    pub fn slice(&mut self) -> &mut [T] {
        unsafe { std::slice::from_raw_parts_mut(self.elements, self.len) }
    }
}

impl<T> Drop for Critical<'_, T> {
    fn drop(&mut self) {
        unsafe {
            let f: ReleaseCriticalFn =
                std::mem::transmute(function(self.env, RELEASE_PRIMITIVE_ARRAY_CRITICAL));
            // 0: write the elements back (a no-op when they were not copied) and let go.
            f(self.env, self.array, self.elements as *mut c_void, 0);
        }
    }
}
