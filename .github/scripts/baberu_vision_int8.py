"""Makes Baberu OCR's vision encoder int8 from its float16 export: weights to float32, then ONNX Runtime's dynamic
quantization. Its int4 export runs about half as fast on CPUs; the decoders are int8 already."""
import sys

import numpy as np
import onnx
from onnx import TensorProto, numpy_helper
from onnxruntime.quantization import QuantType, quantize_dynamic

source, float_path, target = sys.argv[1:4]
model = onnx.load(source)
graph = model.graph
for i, tensor in enumerate(graph.initializer):
    if tensor.data_type == TensorProto.FLOAT16:
        graph.initializer[i].CopyFrom(
            numpy_helper.from_array(numpy_helper.to_array(tensor).astype(np.float32), tensor.name),
        )
for node in graph.node:
    for attribute in node.attribute:
        if node.op_type == 'Cast' and attribute.name == 'to' and attribute.i == TensorProto.FLOAT16:
            attribute.i = TensorProto.FLOAT
        if attribute.type == onnx.AttributeProto.TENSOR and attribute.t.data_type == TensorProto.FLOAT16:
            attribute.t.CopyFrom(
                numpy_helper.from_array(numpy_helper.to_array(attribute.t).astype(np.float32), attribute.t.name),
            )
for value in [*graph.value_info, *graph.input, *graph.output]:
    if value.type.tensor_type.elem_type == TensorProto.FLOAT16:
        value.type.tensor_type.elem_type = TensorProto.FLOAT
onnx.save(model, float_path)
quantize_dynamic(float_path, target, weight_type=QuantType.QInt8)
