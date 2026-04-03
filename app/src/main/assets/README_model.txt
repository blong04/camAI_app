Đặt file model YOLOv11 TensorFlow Lite tại thư mục assets với đúng tên:
- yolo11n_float32.tflite

Gợi ý export từ Ultralytics:
- yolo export model=yolo11n.pt format=tflite imgsz=640

Sau khi copy model vào app/src/main/assets/, sync và build lại project.