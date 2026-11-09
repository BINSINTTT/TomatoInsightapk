package com.tomatoinsight.api

/**
 * Central config for TomatoInsight API integration.
 *
 * The phone sends the photo to the Python server. That server calls the same
 * Ultralytics models as the website: leaf validation first, then disease
 * classification only when the top class is TomatoLeaf.
 */
object ApiConfig {

    /**
     * Your Render web service URL after deploy.
     * Example: https://tomatoinsight-api.onrender.com/
     *
     * Local emulator testing: http://10.0.2.2:5000/
     */

    /**
     * The live website API on Render.
     * Leaf check, disease scores, and care text are three calls on this host.
     */
    const val VALIDATION_SERVER_URL = "https://tomatoinsight.onrender.com/"

    /**
     * Roboflow Serverless Workflow path (used by Python server, not called directly
     * from the app during Stage 1 testing).
     *
     * From: https://serverless.roboflow.com/{WORKFLOW_PATH}
     */
    const val ROBOFLOW_WORKFLOW_PATH =
        "vincent-vfqqy/workflows/tomatoinsightimagevalidation-vtomatoinsightimagevalidation-1-yolov8n-t1-logic"

    const val ROBOFLOW_SERVERLESS_BASE_URL = "https://serverless.roboflow.com/"

    /** Roboflow validator class — single-class object detection model. */
    const val TOMATO_VALIDATOR_CLASS = "TomatoInsightImageValidation"
}
