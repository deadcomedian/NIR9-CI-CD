FROM python:3.9.16-bullseye
WORKDIR /app
COPY app/ /app/

# RUN pip install setuptools==44.0.0
RUN pip install -r requirements.txt

EXPOSE 5000
ENTRYPOINT [ "python" ]
CMD ["app.py" ]