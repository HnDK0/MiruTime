package dev.mirutime.app.features.local.data

/**
 * One ceiling for every local SAF scan/import path. Deeper trees are not a
 * library layout we promise to read; the scanner warns and the importer stops.
 */
const val MAX_LOCAL_SCAN_DEPTH = 12
