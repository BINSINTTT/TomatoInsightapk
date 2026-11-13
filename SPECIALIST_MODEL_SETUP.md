# Early Blight / Spotted Wilt specialist

The mobile pipeline is ready for the fourth YOLOv8n segmentation model with these exact classes:

```yaml
0: leaf
1: early_blight_symptom
2: spotted_wilt_symptom
```

Online scans use the website API route `/refine-disease`. Configure the website deployment with:

```text
ULTRALYTICS_SPECIALIST_URL=<the deployed model /predict URL>
ULTRALYTICS_SPECIALIST_API_KEY=<the Ultralytics platform key>
```

For offline scans, export the trained model as **float32 TFLite** and copy it to:

```text
app/src/main/assets/models/early_spotted_specialist.tflite
```

The app detects the asset automatically. When the model is absent, Early Blight and Spotted Wilt remain unchanged from the four-class disease model. The specialist runs only when one of those two diseases leads.

The offline decoder expects a standard Ultralytics YOLOv8 segmentation detection output with three classes and class scores after `x, y, w, h`. Test the exported artifact on representative images before release because output layout can differ between export versions.
