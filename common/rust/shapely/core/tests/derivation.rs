#![feature(generators)]
#![feature(type_alias_impl_trait)]

use shapely::*;

// =============
// === Utils ===
// =============

/// To fail compilation if `T` is not `IntoIterator`.
fn is_into_iterator<T: IntoIterator>(){}

fn to_vector<T>(t: T) -> Vec<T::Item>
where T: IntoIterator,
      T::Item: Copy {
    t.into_iter().collect()
}

// =====================================
// === Struct with single type param ===
// =====================================

#[derive(Iterator, Eq, PartialEq, Debug)]
pub struct PairTT<T>(T, T);

#[test]
fn derive_iterator_single_t() {
    is_into_iterator::<& PairTT<i32>>();
    is_into_iterator::<&mut PairTT<i32>>();

    let get_pair = || PairTT(4, 49);

    // just collect values
    let pair = get_pair();
    let collected = pair.iter().copied().collect::<Vec<i32>>();
    assert_eq!(collected, vec![4, 49]);

    // IntoIterator for &mut Val
    let mut pair = get_pair();
    for i in &mut pair {
        *i = *i + 1
    }
    assert_eq!(pair, PairTT(5, 50));

    // iter_mut
    for i in pair.iter_mut() {
        *i = *i + 1
    }
    assert_eq!(pair, PairTT(6, 51));

    // IntoIterator for & Val
    let pair = get_pair(); // not mut anymore
    let mut sum = 0;
    for i in &pair {
        sum += i;
    }
    assert_eq!(sum, pair.0 + pair.1)
}

// ===================================
// === Struct with two type params ===
// ===================================

#[derive(Iterator, Eq, PartialEq, Debug)]
pub struct PairUV<U,V>(U,V);

#[test]
fn two_params() {
    // verify that iter uses only the last type param field
    let pair = PairUV(5, 10);
    assert_eq!(to_vector(pair.iter().copied()), vec![10]);
}

// ======================================
// === Struct without any type params ===
// ======================================

#[derive(Iterator, Eq, PartialEq, Debug)]
pub struct Monomorphic(i32);

#[test]
fn no_params() {
    // `derive(Iterator)` is no-op for structures with no type parameters.
    // We just make sure that it does not cause compilation error.
}