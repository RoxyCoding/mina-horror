#version 330 compatibility

out vec4 vertexColor;

void main() {
	vertexColor = gl_Color;
	gl_Position = ftransform();
}
